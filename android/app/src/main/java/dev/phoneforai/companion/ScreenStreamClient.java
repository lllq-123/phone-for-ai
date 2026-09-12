package dev.phoneforai.companion;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.PowerManager;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import okio.ByteString;

/** An explicitly viewed screen session. No recording or inbound network listener. */
final class ScreenStreamClient extends WebSocketListener {
    private static final String VERSION = "4.1";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS).build();
    private static ScreenStreamClient current;
    private final Context context;
    private final String id, token;
    private final File pidFile;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile WebSocket ws;
    private volatile LocalSocket video, control;
    private volatile Process process;
    private volatile DataOutputStream controls;
    private volatile PowerManager.WakeLock wakeLock;
    private volatile long lastServerPing = android.os.SystemClock.elapsedRealtime();
    private int width, height;

    private ScreenStreamClient(Context context, String id, String token) {
        this.context = context.getApplicationContext(); this.id=id; this.token=token;
        this.pidFile = new File(this.context.getFilesDir(), "screen-stream-"+id+".pid");
    }
    static synchronized boolean isActive() { return current != null && !current.stopped.get(); }
    static synchronized String currentId() { return isActive() ? current.id : ""; }
    static synchronized void stopAll() { if (current != null) current.stop(); current = null; }
    static synchronized void sync(Context context, JSONObject desired, String token, String baseUrl) {
        String id = desired == null ? "" : desired.optString("id", "");
        if (current != null && current.id.equals(id)) return;
        if (current != null) current.stop();
        current = null;
        if (!id.matches("[a-f0-9]{32}")) return;
        String path = desired.optString("device_path", "");
        String socketUrl = socketUrl(baseUrl, path, id);
        if (socketUrl == null) return;
        current = new ScreenStreamClient(context,id,token);
        current.ws = HTTP.newWebSocket(new Request.Builder().url(socketUrl).build(), current);
    }
    private static String socketUrl(String baseUrl, String path, String id) {
        try {
            java.net.URI base = new java.net.URI(BridgeClient.normalizeHttpsUrl(baseUrl));
            String expected = base.getPath() + "/stream/" + id + "/device";
            if (!expected.equals(path)) return null;
            return new java.net.URI("wss", null, base.getHost(), base.getPort(), path, null, null).toString();
        } catch (Exception error) { return null; }
    }
    @Override public void onOpen(WebSocket socket, Response response) {
        ws=socket;
        if (stopped.get()) { socket.cancel(); return; }
        try { socket.send(new JSONObject().put("type","auth").put("token",token).toString()); }
        catch (Exception e) { fail("stream_auth_failed"); return; }
        Thread worker=new Thread(this::capture,"phone-screen-video"); worker.start();
    }
    @Override public void onMessage(WebSocket socket, String text) {
        try {
            JSONObject m=new JSONObject(text); String type=m.optString("type");
            if ("stopped".equals(type) || "error".equals(type)) { stop(); return; }
            lastServerPing=android.os.SystemClock.elapsedRealtime();
            if ("ping".equals(type)) { socket.send("{\"type\":\"pong\"}"); return; }
            if ("status".equals(type) || "pong".equals(type)) return;
            synchronized(this) {
                DataOutputStream out=controls;
                if (stopped.get() || out==null) return;
                if ("touch".equals(type)) {
                    int action=m.getInt("action"), x=m.getInt("x"), y=m.getInt("y");
                    int w=m.getInt("width"),h=m.getInt("height"),pointer=m.getInt("pointer_id");
                    if(action<0 || action>3 || pointer<0 || pointer>9 || w!=width || h!=height
                            || x<0 || y<0 || x>=w || y>=h) return;
                    out.writeByte(2);out.writeByte(action);out.writeLong(pointer);
                    out.writeInt(x);out.writeInt(y);out.writeShort(w);out.writeShort(h);
                    out.writeShort(action==1 || action==3 ? 0 : 65535);
                    out.writeInt(0);out.writeInt(0);
                } else if ("key".equals(type)) {
                    int code=m.getInt("keycode");
                    if(code!=3 && code!=4 && code!=187 && code!=24 && code!=25 && code!=66 && code!=67) return;
                    for(int a=0;a<=1;a++){out.writeByte(0);out.writeByte(a);out.writeInt(code);out.writeInt(0);out.writeInt(0);}
                } else if ("text".equals(type)) {
                    String value=m.getString("text");if(value.length()>2000)return;
                    byte[] utf8=value.getBytes(StandardCharsets.UTF_8);
                    out.writeByte(9);out.writeLong(0);out.writeByte(1);out.writeInt(utf8.length);out.write(utf8);
                } else if ("reset_video".equals(type)) { out.writeByte(17); }
                else return;
                out.flush();
            }
        } catch(Exception e) { fail("stream_control_failed"); }
    }
    @Override public void onClosing(WebSocket socket,int code,String reason){ socket.close(code,reason);stop(); }
    @Override public void onClosed(WebSocket socket,int code,String reason){stop();}
    @Override public void onFailure(WebSocket socket,Throwable t,Response response){stop();}

    private void capture() {
        try {
            KeyguardManager keyguard=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);
            if(keyguard==null || (keyguard.isKeyguardLocked() && keyguard.isDeviceSecure())) {
                fail("secure_keyguard_present");return;
            }
            if(!BridgeClient.rootAvailable(context) || PhoneAccessibilityService.isCommandBusy()
                    || DeviceCommandWorker.isBusy()){fail("phone_action_in_progress");return;}
            PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock lock=pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "phoneforai:interactive-stream");
            synchronized(this){if(stopped.get())return;wakeLock=lock;lock.acquire(4*60*60*1000L);}
            if(keyguard.isKeyguardLocked()) {
                context.startActivity(new Intent(context,WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                long deadline=android.os.SystemClock.elapsedRealtime()+5000;
                while(keyguard.isKeyguardLocked() && !stopped.get() && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(100);
                if(keyguard.isKeyguardLocked()){fail("wake_timeout");return;}
            }
            File server=new File(context.getFilesDir(),"scrcpy-server-"+VERSION+".jar");
            // The dex file is replaced only before launch; Android 14 requires read-only dynamic code.
            if(!server.exists()) {
                try(InputStream in=context.getAssets().open("scrcpy-server-v"+VERSION);
                    OutputStream out=new FileOutputStream(server)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
                if(!server.setReadOnly())throw new IOException("read_only_server_failed");
            }
            String scid=id.substring(0,7); // positive 28-bit id, represented as hexadecimal
            String socketName="scrcpy_"+String.format(Locale.ROOT,"%08x",Integer.parseInt(scid,16));
            String command="umask 077; echo $$ > "+DeviceCommandWorker.quote(pidFile.getAbsolutePath())
                    +" && CLASSPATH="+DeviceCommandWorker.quote(server.getAbsolutePath())
                    +" exec /system/bin/app_process / com.genymobile.scrcpy.Server "+VERSION
                    +" scid="+scid+" tunnel_forward=true audio=false control=true clipboard_autosync=false"
                    +" cleanup=false power_on=true stay_awake=false max_size=1280 video_bit_rate=1500000 max_fps=30"
                    +" video_codec=h264 send_device_meta=false send_dummy_byte=true send_frame_meta=true send_stream_meta=true";
            Process child;
            // A stop either prevents launch or sees its process and schedules cleanup.
            synchronized(this){
                if(stopped.get())return;
                child=new ProcessBuilder("su","-M","-c",command).redirectErrorStream(true).start();
                process=child;
            }
            new Thread(()->{try(InputStream in=child.getInputStream()){byte[] b=new byte[1024];while(in.read(b)!=-1){}}
                catch(IOException ignored){}},"phone-screen-log").start();
            LocalSocket v=connect(socketName);
            synchronized(this){if(stopped.get()){v.close();return;}video=v;}
            DataInputStream in=new DataInputStream(v.getInputStream());
            if(in.readUnsignedByte()!=0)throw new IOException("invalid_dummy");
            LocalSocket c=connect(socketName);
            synchronized(this){if(stopped.get()){c.close();return;}control=c;controls=new DataOutputStream(c.getOutputStream());}
            new Thread(()->{try(InputStream ci=c.getInputStream()){byte[] b=new byte[1024];while(ci.read(b)!=-1){}}
                catch(IOException ignored){}},"phone-screen-control").start();
            if(in.readInt()!=0x68323634)throw new IOException("invalid_codec");
            while(!stopped.get()) {
                long header=in.readLong();int size=in.readInt();
                if((header & Long.MIN_VALUE)!=0) {
                    int w=(int)header,h=size;
                    if(w<1||h<1||w>8192||h>8192)throw new IOException("invalid_video_size");
                    synchronized(this){width=w;height=h;}
                    send(new JSONObject().put("type","video").put("codec","h264").put("width",w).put("height",h).toString());
                    continue;
                }
                if(size<=0||size>2*1024*1024-9)throw new IOException("invalid_video_packet");
                byte flags=(byte)(((header&(1L<<62))!=0?1:0)|((header&(1L<<61))!=0?2:0));
                byte[] packet=new byte[size+9];ByteBuffer.wrap(packet).put(flags).putLong(header&((1L<<61)-1));
                in.readFully(packet,9,size);
                WebSocket socket=ws;
                if(socket==null||socket.queueSize()>512*1024||!socket.send(ByteString.of(packet))) {
                    fail("stream_network_slow");return;
                }
            }
        } catch(Exception e) { if(!stopped.get())fail("stream_capture_failed"); }
        finally { stop(); }
    }
    private LocalSocket connect(String name) throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+10000;
        do {
            if(stopped.get())throw new IOException("stopped");
            LocalSocket socket=new LocalSocket();
            try {socket.connect(new LocalSocketAddress(name));return socket;}
            catch(IOException e){socket.close();Thread.sleep(100);}
        } while(android.os.SystemClock.elapsedRealtime()<end);
        throw new IOException("scrcpy_socket_unavailable");
    }
    private void send(String text){WebSocket s=ws;if(s!=null&&!stopped.get())s.send(text);}
    private void fail(String error){
        try{send(new JSONObject().put("type","error").put("error",error).toString());}catch(Exception ignored){}
        stop();
    }
    private synchronized void stop(){
        if(!stopped.compareAndSet(false,true))return;
        close(video);close(control);controls=null;
        WebSocket socket=ws;if(socket!=null)socket.close(1000,"screen_closed");
        Process child=process;if(child!=null)new Thread(()->stopServer(child),"phone-screen-stop").start();
        PowerManager.WakeLock lock=wakeLock;if(lock!=null&&lock.isHeld())lock.release();
        BridgeClient.heartbeatNow(context);
    }
    private void stopServer(Process child){
        // Magisk's su client may exit while app_process survives. The per-session
        // PID receipt also covers cancellation before either local socket connects.
        String receipt=DeviceCommandWorker.quote(pidFile.getAbsolutePath());
        String signature=DeviceCommandWorker.quote("com.genymobile.scrcpy.Server "+VERSION+" scid="+id.substring(0,7)+" ");
        String command="p="+receipt+"; n=0; while [ \"$n\" -lt 100 ]; do "
                +"if [ -f \"$p\" ]; then read -r pid < \"$p\"; case \"$pid\" in ''|*[!0-9]*) ;; *) "
                +"cmd=$(tr '\\000' ' ' < /proc/\"$pid\"/cmdline 2>/dev/null); "
                +"case \"$cmd\" in *"+signature+"*) kill -TERM \"$pid\" 2>/dev/null; rm -f \"$p\"; exit 0;; esac; "
                +"if [ ! -d /proc/\"$pid\" ]; then rm -f \"$p\"; exit 0; fi;; esac; fi; n=$((n+1)); sleep 0.1; done";
        Process cleanup=null;
        try{
            cleanup=new ProcessBuilder("su","-M","-c",command).redirectErrorStream(true).start();
            cleanup.waitFor(12,TimeUnit.SECONDS);
        }catch(Exception ignored){}
        finally{if(cleanup!=null)cleanup.destroy();child.destroy();}
    }
    private static void close(LocalSocket socket){if(socket!=null)try{socket.close();}catch(IOException ignored){}}
}
