package dev.phoneforai.companion;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Explicitly enabled Root shell, file, and package operations. */
final class DeviceCommandWorker {
    interface ProbeCallback { void done(boolean available, String detail); }
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final int MAX_BYTES = 49152;
    private static final long MAX_OFFSET = 9007199254740991L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private DeviceCommandWorker() {}

    static boolean handles(String type) {
        return "root.exec".equals(type) || "files.list".equals(type) || "files.read".equals(type)
                || "files.write".equals(type) || "apps.list".equals(type);
    }
    static boolean isBusy() { return BUSY.get(); }

    static void probeRoot(Context context, ProbeCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                BoundedProcessRunner.Result result = run("exec /system/bin/id -u", true, null, 5, 128, true);
                ok = succeeded(result) && "0".equals(new String(result.stdout, StandardCharsets.UTF_8).trim());
            } catch (Exception ignored) { }
            BridgeClient.setRootState(app, ok, ok);
            callback.done(ok, ok ? "Root 扩展已开启" : "Root 授权不可用，扩展保持关闭");
        });
    }

    static void submit(Context context, JSONObject command) {
        Context app = context.getApplicationContext();
        String id = command.optString("id", "");
        if (!ID.matcher(id).matches()) return;
        if (ScreenStreamClient.isActive()) {
            BridgeClient.postFailure(app, id, "phone_stream_in_use");
            return;
        }
        if (!BridgeClient.rootAvailable(app)) {
            BridgeClient.postFailure(app, id, "root_extension_unavailable");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            BridgeClient.postFailure(app, id, "device_command_busy");
            return;
        }
        String serialized = command.toString();
        EXECUTOR.execute(() -> {
            try {
                JSONObject copy = new JSONObject(serialized);
                JSONObject args = copy.optJSONObject("args");
                if (args == null) throw new IllegalArgumentException();
                execute(app, id, copy.optString("type", ""), args);
            } catch (IllegalArgumentException error) {
                BridgeClient.postFailure(app, id, "invalid_command_args");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                BridgeClient.postFailure(app, id, "command_interrupted");
            } catch (Exception error) {
                BridgeClient.postFailure(app, id, "command_process_failed");
            } finally { BUSY.set(false); }
        });
    }

    private static void execute(Context context, String id, String type, JSONObject args) throws Exception {
        if ("root.exec".equals(type)) {
            BoundedProcessRunner.Result result = run(string(args, "command", null, 16000),
                    bool(args, "root", true), null, integer(args, "timeout_seconds", 15, 1, 30),
                    maxBytes(args), true);
            JSONObject data = outputResult(result);
            if (result.ioFailed && !result.timedOut) data.put("detail", "process_io_failed");
            if (succeeded(result)) BridgeClient.postData(context, id, data);
            else BridgeClient.postFailure(context, id, "root_command_failed", data);
            return;
        }
        if ("files.list".equals(type)) {
            String path = path(args);
            BoundedProcessRunner.Result result = run("exec /system/bin/toybox ls -la -- " + quote(path),
                    true, null, 15, maxBytes(args), true);
            finish(context, id, result, outputResult(result).put("path", path), "file_list_failed");
            return;
        }
        if ("files.read".equals(type)) { read(context, id, args); return; }
        if ("files.write".equals(type)) { write(context, id, args); return; }
        if ("apps.list".equals(type)) {
            String query = string(args, "query", "", 160);
            String command = "exec /system/bin/pm list packages -f "
                    + (bool(args, "user_only", false) ? "-3 " : "") + "-- " + quote(query);
            BoundedProcessRunner.Result result = run(command, true, null, 15, maxBytes(args), true);
            finish(context, id, result, outputResult(result), "app_inventory_failed");
            return;
        }
        throw new IllegalArgumentException();
    }

    private static void read(Context context, String id, JSONObject args) throws Exception {
        String path = path(args);
        long offset = number(args, "offset", 0, 0, MAX_OFFSET);
        int limit = maxBytes(args);
        String command = "exec /system/bin/toybox dd " + quote("if=" + path)
                + " bs=65536 iflag=skip_bytes,count_bytes skip=" + offset
                + " count=" + (limit + 1) + " status=none";
        BoundedProcessRunner.Result result = run(command, true, null, 15, limit + 1, false);
        JSONObject data = executionResult(result).put("path", path).put("offset", offset);
        if (!succeeded(result)) {
            BridgeClient.postFailure(context, id, "file_read_failed", data); return;
        }
        int count = Math.min(limit, result.stdout.length);
        boolean eof = result.stdout.length <= limit;
        data.put("data_base64", Base64.encodeToString(Arrays.copyOf(result.stdout, count), Base64.NO_WRAP))
                .put("next_offset", offset + count).put("eof", eof);
        BridgeClient.postData(context, id, data);
    }

    private static void write(Context context, String id, JSONObject args) throws Exception {
        String path = path(args);
        String encoded = string(args, "data_base64", null, 65536);
        byte[] bytes;
        try {
            bytes = Base64.decode(encoded, Base64.NO_WRAP);
            if (bytes.length > MAX_BYTES || !Base64.encodeToString(bytes, Base64.NO_WRAP).equals(encoded))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new IllegalArgumentException(); }
        String command = "umask 077; /system/bin/toybox cat "
                + (bool(args, "append", false) ? ">> " : "> ") + quote(path);
        BoundedProcessRunner.Result result = run(command, true, bytes, 15, 4096, false);
        JSONObject data = executionResult(result).put("path", path);
        if (succeeded(result)) BridgeClient.postData(context, id, data.put("bytes_written", bytes.length));
        else BridgeClient.postFailure(context, id, "file_write_failed", data);
    }

    private static BoundedProcessRunner.Result run(String script, boolean root, byte[] input,
            int timeout, int limit, boolean merge) throws IOException, InterruptedException {
        String timed = "exec /system/bin/toybox timeout -k 1 " + timeout
                + " /system/bin/sh -c " + quote(script);
        String[] argv = root ? new String[]{"su", "-M", "-c", timed}
                : new String[]{"/system/bin/sh", "-c", timed};
        return BoundedProcessRunner.run(argv, input, timeout, limit, merge);
    }
    private static void finish(Context context, String id, BoundedProcessRunner.Result result,
            JSONObject data, String error) {
        if (succeeded(result)) BridgeClient.postData(context, id, data);
        else BridgeClient.postFailure(context, id, error, data);
    }
    private static boolean succeeded(BoundedProcessRunner.Result r) {
        return r.exitCode == 0 && !r.timedOut && !r.ioFailed;
    }
    private static JSONObject outputResult(BoundedProcessRunner.Result r) throws Exception {
        return executionResult(r).put("output_base64", Base64.encodeToString(r.stdout, Base64.NO_WRAP));
    }
    private static JSONObject executionResult(BoundedProcessRunner.Result r) throws Exception {
        return new JSONObject().put("exit_code", r.exitCode).put("timed_out", r.timedOut)
                .put("truncated", r.truncated);
    }
    static String quote(String value) {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException();
        return "'" + value.replace("'", "'\\''") + "'";
    }
    private static String path(JSONObject args) throws Exception {
        String path = string(args, "path", null, 4096);
        if (!path.startsWith("/")) throw new IllegalArgumentException();
        return path;
    }
    private static String string(JSONObject args, String name, String fallback, int max) throws Exception {
        if (!args.has(name) && fallback != null) return fallback;
        Object raw = args.get(name);
        if (!(raw instanceof String)) throw new IllegalArgumentException();
        String value = (String) raw;
        if (value.codePointCount(0, value.length()) > max || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException();
        return value;
    }
    private static boolean bool(JSONObject args, String name, boolean fallback) throws Exception {
        if (!args.has(name)) return fallback;
        Object raw = args.get(name);
        if (!(raw instanceof Boolean)) throw new IllegalArgumentException();
        return (boolean) raw;
    }
    private static int maxBytes(JSONObject args) throws Exception {
        return integer(args, "max_bytes", 32768, 1, MAX_BYTES);
    }
    private static int integer(JSONObject args, String name, int fallback, int min, int max) throws Exception {
        return (int) number(args, name, fallback, min, max);
    }
    private static long number(JSONObject args, String name, long fallback, long min, long max) throws Exception {
        if (!args.has(name)) return fallback;
        Object raw = args.get(name);
        if (!(raw instanceof Number)) throw new IllegalArgumentException();
        Number value = (Number) raw;
        long number = value.longValue();
        if (number < min || number > max || value.doubleValue() != (double) number)
            throw new IllegalArgumentException();
        return number;
    }
}
