package dev.phoneforai.companion;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.ByteString;

/** An explicitly viewed screen session. No recording or inbound network listener. */
final class ScreenStreamClient extends WebSocketListener {
    private static final String TAG = "PhoneForAI";
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
    private final AtomicReference<LocalServerSocket> listener=new AtomicReference<>();
    private volatile Process process;
    private volatile Integer exitCode;
    private volatile int stage=ScrcpyLaunch.PREPARING;
    private volatile boolean serverStarted, timedOut;
    // Recent su output, kept only to classify launch failures on the phone; never sent.
    private final StringBuffer serverLog=new StringBuffer();
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
            File server=new File(context.getFilesDir(),"scrcpy-server-"+ScrcpyLaunch.VERSION+".jar");
            // The dex file is replaced only before launch; Android 14 requires read-only dynamic code.
            if(!server.exists()) {
                try(InputStream in=context.getAssets().open("scrcpy-server-v"+ScrcpyLaunch.VERSION);
                    OutputStream out=new FileOutputStream(server)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
                if(!server.setReadOnly())throw new IOException("read_only_server_failed");
            }
            String scid=id.substring(0,7); // positive 28-bit id, represented as hexadecimal
            String command=ScrcpyLaunch.serverCommand(pidFile.getAbsolutePath(),server.getAbsolutePath(),scid);
            // scrcpy connects once per socket without retrying, so listen before launching it.
            LocalServerSocket accepting=new LocalServerSocket(ScrcpyLaunch.socketName(scid));
            long deadline=android.os.SystemClock.elapsedRealtime()+10000;
            Process child;
            // A stop either prevents launch or sees its process and schedules cleanup.
            synchronized(this){
                listener.set(accepting);
                if(stopped.get()){closeListener();return;}
                stage=ScrcpyLaunch.STARTING;
                child=new ProcessBuilder("su","-M","-c",command).redirectErrorStream(true).start();
                process=child;stage=ScrcpyLaunch.ACCEPTING;
            }
            Thread reader=new Thread(()->{try(InputStream in=child.getInputStream()){
                byte[] b=new byte[1024];int n;String tail="";
                while((n=in.read(b))!=-1){
                    String chunk=new String(b,0,n,StandardCharsets.ISO_8859_1);
                    // A start marker can straddle two reads, so carry a little context across.
                    String spanning=tail+chunk;
                    if(spanning.contains("[server]"))serverStarted=true;
                    tail=spanning.length()>8?spanning.substring(spanning.length()-8):spanning;
                    serverLog.append(chunk);
                    if(serverLog.length()>4096)serverLog.delete(0,serverLog.length()-4096);}}
                catch(IOException ignored){}},"phone-screen-log");
            reader.start();
            new Thread(()->{
                try{
                    if(child.waitFor(deadline-android.os.SystemClock.elapsedRealtime(),TimeUnit.MILLISECONDS)){
                        reader.join(1000);exitCode=child.exitValue();
                        // Magisk's su may return 0 before app_process exits (scrcpy drops to the shell uid
                        // at startup), so only an explicit refusal ends the wait before the deadline.
                        if(!serverStarted&&ScrcpyLaunch.denied(exitCode,serverLog.toString())){
                            if(stage!=ScrcpyLaunch.STREAMING)closeListener();return;}
                        long rest=deadline-android.os.SystemClock.elapsedRealtime();
                        if(rest>0)Thread.sleep(rest);
                    }
                    if(stage!=ScrcpyLaunch.STREAMING){timedOut=true;closeListener();}
                }catch(InterruptedException e){if(stage!=ScrcpyLaunch.STREAMING)closeListener();}
            },"phone-screen-exit").start();
            LocalSocket v=accept(accepting);
            synchronized(this){if(stopped.get()){v.close();return;}video=v;}
            LocalSocket c=accept(accepting);
            synchronized(this){if(stopped.get()){c.close();return;}control=c;controls=new DataOutputStream(c.getOutputStream());}
            stage=ScrcpyLaunch.STREAMING;closeListener();
            new Thread(()->{try(InputStream ci=c.getInputStream()){byte[] b=new byte[1024];while(ci.read(b)!=-1){}}
                catch(IOException ignored){}},"phone-screen-control").start();
            DataInputStream in=new DataInputStream(v.getInputStream());
            v.setSoTimeout(10000); // fail fast if scrcpy never sends a first frame
            if(in.readInt()!=0x68323634)throw new IOException("invalid_codec");
            boolean gotFrame=false;
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
                if(!gotFrame){gotFrame=true;v.setSoTimeout(0);}
                byte flags=(byte)(((header&(1L<<62))!=0?1:0)|((header&(1L<<61))!=0?2:0));
                byte[] packet=new byte[size+9];ByteBuffer.wrap(packet).put(flags).putLong(header&((1L<<61)-1));
                in.readFully(packet,9,size);
                WebSocket socket=ws;
                if(socket==null||socket.queueSize()>512*1024||!socket.send(ByteString.of(packet))) {
                    fail("stream_network_slow");return;
                }
            }
        } catch(Exception e) {
            if(!stopped.get()){
                String code=ScrcpyLaunch.failure(stage,exitCode,timedOut,serverStarted,serverLog.toString());
                Log.w(TAG,"stream failed stage="+stage+" exit="+exitCode+" timedOut="+timedOut
                        +" started="+serverStarted+" code="+code,e);
                fail(code);
            }
        }
        finally { stop(); }
    }
    private static LocalSocket accept(LocalServerSocket server) throws IOException {
        while(true) {
            LocalSocket socket=server.accept();
            try{
                android.net.Credentials peer=socket.getPeerCredentials();
                // scrcpy drops from root to the shell uid at startup so the clipboard works.
                if(peer.getUid()==0||peer.getUid()==2000)return socket;
                Log.w(TAG,"rejected stream peer uid="+peer.getUid()+" pid="+peer.getPid());
            }catch(IOException e){Log.w(TAG,"stream peer credentials unavailable",e);}
            close(socket);
        }
    }
    private void send(String text){WebSocket s=ws;if(s!=null&&!stopped.get())s.send(text);}
    private void fail(String error){
        try{send(new JSONObject().put("type","error").put("error",error).toString());}catch(Exception ignored){}
        stop();
    }
    private synchronized void stop(){
        if(!stopped.compareAndSet(false,true))return;
        close(video);close(control);closeListener();controls=null;
        WebSocket socket=ws;if(socket!=null)socket.close(1000,"screen_closed");
        Process child=process;if(child!=null)new Thread(()->stopServer(child),"phone-screen-stop").start();
        PowerManager.WakeLock lock=wakeLock;if(lock!=null&&lock.isHeld())lock.release();
        BridgeClient.heartbeatNow(context);
    }
    private void stopServer(Process child){
        // Only an explicit su refusal proves app_process never ran; a second su call would then
        // just pop another auth prompt, so skip the cleanup in that case alone.
        if(exitCode!=null&&!serverStarted&&ScrcpyLaunch.denied(exitCode,serverLog.toString())){child.destroy();return;}
        // Magisk's su client may exit while app_process survives. The per-session
        // PID receipt also covers cancellation before either local socket connects.
        String receipt=DeviceCommandWorker.quote(pidFile.getAbsolutePath());
        String signature=DeviceCommandWorker.quote("com.genymobile.scrcpy.Server "+ScrcpyLaunch.VERSION+" scid="+id.substring(0,7)+" ");
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
    // Close the listener at most once: whoever wins getAndSet shuts down a live fd, never a reused one.
    private void closeListener(){
        LocalServerSocket socket=listener.getAndSet(null);
        if(socket==null)return;
        // close() alone does not wake a thread blocked in accept().
        try{Os.shutdown(socket.getFileDescriptor(),OsConstants.SHUT_RDWR);}catch(Exception ignored){}
        try{socket.close();}catch(IOException ignored){}
    }
}
