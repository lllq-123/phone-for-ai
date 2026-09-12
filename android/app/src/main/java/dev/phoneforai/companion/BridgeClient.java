package dev.phoneforai.companion;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

final class BridgeClient {
    interface Callback { void done(boolean ok, String detail); }
    interface ScreenshotCallback { void done(boolean artifactUploaded); }

    private static final String PREFS = "phone_for_ai";
    private static final String BASE = "base_url";
    private static final String DEVICE_ID = "device_id";
    private static final String TOKEN = "device_token";
    private static final String LAST_OK = "last_ok";
    private static final String LAST_ERROR = "last_error";
    private static final String ROOT_ENABLED = "root_enabled";
    private static final String ROOT_AVAILABLE = "root_available";
    private static final Pattern COMMAND_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final ScheduledExecutorService HEARTBEAT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor();
    private static final ScheduledExecutorService NETWORK_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor();
    private static final AtomicReference<Context> CONTEXT = new AtomicReference<>();
    private static final HeartbeatPoller POLLER = new HeartbeatPoller(
            HEARTBEAT_EXECUTOR, () -> new HeartbeatRequest(CONTEXT.get()));

    private BridgeClient() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    static String baseUrl(Context context) { return prefs(context).getString(BASE, ""); }
    static boolean isPaired(Context context) {
        return !baseUrl(context).isEmpty() && !prefs(context).getString(DEVICE_ID, "").isEmpty()
                && !prefs(context).getString(TOKEN, "").isEmpty();
    }
    static boolean rootEnabled(Context context) { return prefs(context).getBoolean(ROOT_ENABLED, false); }
    static boolean rootAvailable(Context context) {
        return rootEnabled(context) && prefs(context).getBoolean(ROOT_AVAILABLE, false);
    }
    static void setRootState(Context context, boolean enabled, boolean available) {
        prefs(context).edit().putBoolean(ROOT_ENABLED, enabled)
                .putBoolean(ROOT_AVAILABLE, enabled && available).apply();
        if (!enabled || !available) ScreenStreamClient.stopAll();
    }
    static String status(Context context) {
        if (!isPaired(context)) return "未配对";
        String error = prefs(context).getString(LAST_ERROR, "");
        if (!error.isEmpty()) return "已配对 · 连接失败（" + error + "）";
        return prefs(context).getLong(LAST_OK, 0L) > 0 ? "已配对 · 最近连接成功" : "已配对 · 等待首次连接";
    }

    static String normalizeHttpsUrl(String raw) throws IllegalArgumentException {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URL url = new URL(value);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null)
                throw new IllegalArgumentException("请输入完整的 HTTPS API 地址");
            return value;
        } catch (IOException error) {
            throw new IllegalArgumentException("请输入完整的 HTTPS API 地址");
        }
    }

    static void enroll(Context context, String rawBase, String rawCode, Callback callback) {
        Context app = context.getApplicationContext();
        final String base;
        try { base = normalizeHttpsUrl(rawBase); }
        catch (IllegalArgumentException error) { callback.done(false, error.getMessage()); return; }
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty() || code.length() > 512) { callback.done(false, "请输入有效的一次性配对码"); return; }
        NETWORK_EXECUTOR.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("enrollment_code", code)
                        .put("app_version", appVersion(app)).put("capabilities", capabilities(app));
                JSONObject response = post(base, "/enroll", body, null, null);
                String id = response.optString("device_id", "");
                String token = response.optString("device_token", "");
                if (id.isEmpty() || token.isEmpty()) throw new IOException("配对响应缺少设备凭据");
                prefs(app).edit().putString(BASE, base).putString(DEVICE_ID, id)
                        .putString(TOKEN, token).putString(LAST_ERROR, "").apply();
                start(app); heartbeatNow(app);
                callback.done(true, "配对成功");
            } catch (Exception error) { callback.done(false, safeError(error)); }
        });
    }

    static void unpair(Context context) {
        ScreenStreamClient.stopAll();
        prefs(context).edit().remove(DEVICE_ID).remove(TOKEN).remove(LAST_OK)
                .remove(LAST_ERROR).apply();
        POLLER.stop();
    }
    static void start(Context context) {
        CONTEXT.set(context.getApplicationContext());
        if (isPaired(context)) POLLER.start();
    }
    static void heartbeatNow(Context context) {
        CONTEXT.set(context.getApplicationContext());
        if (isPaired(context)) { POLLER.start(); POLLER.requestNow(); }
    }

    private static Long heartbeat(Context context, HeartbeatRequest owner) {
        if (!isPaired(context)) return null;
        try {
            JSONObject request = new JSONObject().put("app_version", appVersion(context))
                    .put("capabilities", capabilities(context)).put("status", deviceStatus(context))
                    .put("long_poll_seconds", 20);
            String knownStreamId = ScreenStreamClient.currentId();
            if (!knownStreamId.isEmpty()) request.put("known_stream_id", knownStreamId);
            JSONObject response = post(baseUrl(context), "/heartbeat", request,
                    prefs(context).getString(TOKEN, ""), owner);
            prefs(context).edit().putLong(LAST_OK, System.currentTimeMillis()).putString(LAST_ERROR, "").apply();
            JSONObject command = response.optJSONObject("command");
            if (command != null) dispatch(context, command);
            ScreenStreamClient.sync(context, response.optJSONObject("stream"),
                    prefs(context).getString(TOKEN, ""), baseUrl(context));
            return HeartbeatTiming.successDelayMillis(response.opt("long_poll_seconds"),
                    response.opt("heartbeat_interval_seconds"));
        } catch (Exception error) {
            if (!owner.cancelled()) prefs(context).edit().putString(LAST_ERROR, safeError(error)).apply();
            return null;
        }
    }

    private static void dispatch(Context context, JSONObject command) {
        String id = command.optString("id", "");
        String type = command.optString("type", "");
        if (!COMMAND_ID.matcher(id).matches() || command.optJSONObject("args") == null) return;
        if (DeviceCommandWorker.handles(type)) DeviceCommandWorker.submit(context, command);
        else if (!PhoneAccessibilityService.submit(command)) postFailure(context, id, "accessibility_unavailable");
    }

    static void postData(Context context, String id, JSONObject result) { postResult(context, id, true, result, null); }
    static void postFailure(Context context, String id, String error) {
        postResult(context, id, false, new JSONObject(), error);
    }
    static void postFailure(Context context, String id, String error, JSONObject result) {
        postResult(context, id, false, result, error);
    }
    private static void postResult(Context context, String id, boolean ok, JSONObject result, String error) {
        Context app = context.getApplicationContext();
        NETWORK_EXECUTOR.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("ok", ok).put("result", result);
                if (error != null && !error.isEmpty()) body.put("error", error);
                retryPost(app, "/commands/" + id + "/result", body);
            } catch (Exception ignored) { }
        });
    }

    static void postScreenshot(Context context, String id, JSONObject result, byte[] jpeg,
            ScreenshotCallback callback) {
        Context app = context.getApplicationContext();
        NETWORK_EXECUTOR.execute(() -> {
            boolean uploaded = false;
            try {
                postBytes(baseUrl(app), "/commands/" + id + "/artifact", jpeg,
                        prefs(app).getString(TOKEN, ""));
                uploaded = true;
            } catch (Exception ignored) { }
            try {
                if (!uploaded) result.put("artifact_upload_failed", true);
                JSONObject body = new JSONObject().put("ok", true).put("result", result);
                retryPost(app, "/commands/" + id + "/result", body);
            } catch (Exception ignored) { }
            boolean finalUploaded = uploaded;
            if (callback != null) callback.done(finalUploaded);
        });
    }

    private static void retryPost(Context context, String path, JSONObject body) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                post(baseUrl(context), path, body, prefs(context).getString(TOKEN, ""), null);
                return;
            } catch (Exception error) {
                last = error;
                if (attempt < 2) Thread.sleep(500L << attempt);
            }
        }
        throw last;
    }

    private static JSONObject capabilities(Context context) throws Exception {
        boolean access = PhoneAccessibilityService.isConnected();
        boolean root = rootAvailable(context);
        return new JSONObject().put("accessibility", access).put("screen_capture", access)
                .put("wake_screen", access).put("gestures", access).put("text_input", access)
                .put("launch", access).put("ui_tree", access).put("clipboard", access)
                .put("root_shell", root).put("file_access", root).put("app_inventory", root)
                .put("screen_stream", root);
    }

    private static JSONObject deviceStatus(Context context) throws Exception {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        JSONObject status = new JSONObject().put("accessibility", PhoneAccessibilityService.isConnected())
                .put("screen_on", power != null && power.isInteractive()).put("plugged", plugged != 0);
        if (level >= 0 && scale > 0) status.put("battery_pct", Math.round(level * 100f / scale));
        return status;
    }

    private static JSONObject post(String base, String path, JSONObject body, String token,
            HeartbeatRequest owner) throws Exception {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(base, path, token, "application/json; charset=utf-8");
        connection.setReadTimeout(owner == null ? 20_000 : 30_000);
        connection.setFixedLengthStreamingMode(payload.length);
        if (owner != null) owner.attach(connection);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            return response(connection);
        } finally {
            if (owner != null) owner.detach(connection);
            connection.disconnect();
        }
    }
    private static void postBytes(String base, String path, byte[] payload, String token) throws Exception {
        if (payload.length > 3 * 1024 * 1024) throw new IOException("artifact_too_large");
        HttpURLConnection connection = open(base, path, token, "image/jpeg");
        connection.setReadTimeout(20_000); connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            response(connection);
        } finally { connection.disconnect(); }
    }
    private static HttpURLConnection open(String base, String path, String token, String type) throws Exception {
        URL url = new URL(normalizeHttpsUrl(base) + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST"); connection.setConnectTimeout(10_000);
        connection.setDoOutput(true); connection.setRequestProperty("Content-Type", type);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }
    private static JSONObject response(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] bytes = readBounded(stream, 1024 * 1024);
        JSONObject response = bytes.length == 0 ? new JSONObject() : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (status < 200 || status >= 300 || !response.optBoolean("ok", false))
            throw new IOException(response.optString("error", "http_" + status));
        return response;
    }
    private static byte[] readBounded(InputStream stream, int max) throws IOException {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > max) throw new IOException("response_too_large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    private static String appVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "unknown"; }
    }
    private static String safeError(Exception error) {
        String value = error.getMessage();
        if (value == null || value.trim().isEmpty()) value = error.getClass().getSimpleName();
        value = value.trim().replaceAll("\\s+", " ");
        return value.substring(0, Math.min(value.length(), 96));
    }

    private static final class HeartbeatRequest implements HeartbeatPoller.Request {
        private final Context context;
        private HttpURLConnection connection;
        private boolean cancelled;
        private long delay = 2000L;
        HeartbeatRequest(Context context) { this.context = context; }
        @Override public boolean execute() {
            Long value = context == null ? null : heartbeat(context, this);
            if (value == null) return false;
            delay = value; return true;
        }
        @Override public long successDelayMillis() { return delay; }
        synchronized void attach(HttpURLConnection value) throws IOException {
            if (cancelled) { value.disconnect(); throw new InterruptedIOException("heartbeat_cancelled"); }
            connection = value;
        }
        synchronized void detach(HttpURLConnection value) { if (connection == value) connection = null; }
        synchronized boolean cancelled() { return cancelled; }
        @Override public void cancel() {
            HttpURLConnection value;
            synchronized (this) { cancelled = true; value = connection; connection = null; }
            if (value != null) value.disconnect();
        }
    }
}
