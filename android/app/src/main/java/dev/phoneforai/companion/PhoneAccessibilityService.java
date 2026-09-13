package dev.phoneforai.companion;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.regex.Pattern;

public final class PhoneAccessibilityService extends AccessibilityService {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$");
    private static final long BASIS_MAX_AGE_MS = 180_000L;
    private static final int WAKE_MAX_ATTEMPTS = 40;
    private static final long WAKE_RETRY_DELAY_MS = 150L;
    private static final long WAKE_UNLOCK_READY_DELAY_MS = 700L;
    private static final long WAKE_SETTLE_DELAY_MS = 1_200L;
    private static volatile PhoneAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy;
    private String activeId = "";
    private String foregroundPackage = "";
    private boolean basisValid;
    private String basisId = "";
    private String basisPackage = "";
    private int basisWidth;
    private int basisHeight;
    private long basisCapturedAt;
    private boolean wakeDismissGestureScheduled;
    private boolean wakeDismissGestureDispatched;
    private BroadcastReceiver screenPowerReceiver;
    private PowerManager.WakeLock lockedScreenWakeLock;

    static boolean isConnected() { return instance != null; }
    static boolean isCommandBusy() { PhoneAccessibilityService service = instance; return service != null && service.busy; }
    static void requestScreenPowerStateSync() {
        PhoneAccessibilityService expected = instance;
        if (expected == null) return;
        expected.handler.post(() -> {
            // A queued heartbeat correction must not reacquire a lock after this
            // accessibility-service instance has been destroyed or replaced.
            if (instance == expected) expected.updateLockedScreenWakeLock();
        });
    }
    static boolean submit(JSONObject command) {
        PhoneAccessibilityService service = instance;
        if (service == null || command == null) return false;
        String serialized = command.toString();
        service.handler.post(() -> {
            try { service.execute(new JSONObject(serialized)); }
            catch (Exception error) { service.fail("", "invalid_command"); }
        });
        return true;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        registerScreenPowerReceiver();
        updateLockedScreenWakeLock();
        BridgeClient.start(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            String value = event.getPackageName().toString();
            if (PACKAGE.matcher(value).matches()) foregroundPackage = value;
        }
    }
    @Override public void onInterrupt() {
        basisValid = false;
        if (busy) fail(activeId, "accessibility_interrupted");
    }
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        basisValid = false;
        unregisterScreenPowerReceiver();
        releaseLockedScreenWakeLock();
        super.onDestroy();
    }

    private void execute(JSONObject command) {
        String id = command.optString("id", "");
        String type = command.optString("type", "");
        JSONObject args = command.optJSONObject("args");
        if (!ID.matcher(id).matches() || args == null) { fail(id, "invalid_command"); return; }
        if (ScreenStreamClient.isActive()) { fail(id, "phone_stream_in_use"); return; }
        if (busy) { fail(id, "accessibility_busy"); return; }
        ScreenshotOptions options;
        try { options = ScreenshotOptions.forCommand(type,
                args.has("max_width") ? args.opt("max_width") : null,
                args.has("quality") ? args.opt("quality") : null); }
        catch (IllegalArgumentException error) { fail(id, "invalid_command_args"); return; }

        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if ("screen.wake".equals(type)) { wake(id, options, keyguard); return; }
        if (keyguard != null && keyguard.isDeviceLocked()) { fail(id, "device_locked"); return; }
        if ("screen.capture".equals(type)) { begin(id); capture(id, options); return; }
        if ("app.launch".equals(type)) {
            String packageName = args.optString("package", "");
            if (!PACKAGE.matcher(packageName).matches()) { fail(id, "invalid_command_args"); return; }
            begin(id); basisValid = false; launch(id, packageName, options); return;
        }
        if ("ui.dump".equals(type)) { begin(id); dump(id, args); return; }
        if ("clipboard.set".equals(type)) { begin(id); setClipboard(id, args); return; }
        if (!isUiAction(type)) { fail(id, "unsupported_command"); return; }
        if (!validBasis(args)) { fail(id, "stale_screen_basis"); return; }
        begin(id);
        basisValid = false;
        if ("ui.type".equals(type)) { type(id, args, options); return; }
        if (type.startsWith("ui.global.")) { global(id, type, options); return; }
        gesture(id, type, args, options);
    }

    private static boolean isUiAction(String type) {
        return "ui.tap".equals(type) || "ui.long_press".equals(type) || "ui.swipe".equals(type)
                || "ui.type".equals(type) || "ui.global.back".equals(type)
                || "ui.global.home".equals(type) || "ui.global.recents".equals(type);
    }
    private boolean validBasis(JSONObject args) {
        String expected = args.optString("expected_package", "");
        return basisValid && basisId.equals(args.optString("basis_command_id", ""))
                && basisPackage.equals(expected) && expected.equals(currentPackage())
                && SystemClock.elapsedRealtime() - basisCapturedAt <= BASIS_MAX_AGE_MS;
    }
    private void begin(String id) { busy = true; activeId = id; }

    private void wake(String id, ScreenshotOptions options, KeyguardManager keyguard) {
        if (keyguard == null) { fail(id, "keyguard_service_missing"); return; }
        if (WakeStatePolicy.secureKeyguardBlocks(
                keyguard.isKeyguardLocked(), keyguard.isDeviceSecure())) {
            fail(id, "secure_keyguard_present");
            return;
        }
        begin(id);
        basisValid = false;
        wakeDismissGestureScheduled = false;
        wakeDismissGestureDispatched = false;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null && WakeStatePolicy.readyToCapture(
                power.isInteractive(), keyguard.isKeyguardLocked())) {
            captureWakeAfterSettle(id, options);
            return;
        }
        Intent intent = new Intent(this, WakeActivity.class).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try { startActivity(intent); }
        catch (RuntimeException error) { fail(id, "wake_activity_rejected"); return; }
        handler.postDelayed(() -> awaitWakeAndCapture(id, options, 0), WAKE_RETRY_DELAY_MS);
    }

    private void awaitWakeAndCapture(String id, ScreenshotOptions options, int attempt) {
        if (!busy || !id.equals(activeId)) return;
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (keyguard == null || power == null) { fail(id, "wake_service_missing"); return; }
        boolean keyguardLocked = keyguard.isKeyguardLocked();
        if (WakeStatePolicy.secureKeyguardBlocks(keyguardLocked, keyguard.isDeviceSecure())) {
            fail(id, "secure_keyguard_present");
            return;
        }
        boolean interactive = power.isInteractive();
        if (WakeStatePolicy.readyToCapture(interactive, keyguardLocked)) {
            captureWakeAfterSettle(id, options);
            return;
        }
        if (WakeStatePolicy.shouldDispatchUnlockGesture(
                interactive, keyguardLocked,
                wakeDismissGestureScheduled || wakeDismissGestureDispatched)) {
            scheduleWakeDismissGesture(id, options, attempt);
            return;
        }
        if (attempt >= WAKE_MAX_ATTEMPTS) { fail(id, "wake_timeout"); return; }
        handler.postDelayed(
                () -> awaitWakeAndCapture(id, options, attempt + 1),
                WAKE_RETRY_DELAY_MS);
    }

    private void scheduleWakeDismissGesture(
            String id,
            ScreenshotOptions options,
            int attempt
    ) {
        wakeDismissGestureScheduled = true;
        handler.postDelayed(() -> {
            if (!busy || !id.equals(activeId)) return;
            wakeDismissGestureScheduled = false;
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (keyguard == null || power == null) { fail(id, "wake_service_missing"); return; }
            boolean keyguardLocked = keyguard.isKeyguardLocked();
            if (WakeStatePolicy.secureKeyguardBlocks(keyguardLocked, keyguard.isDeviceSecure())) {
                fail(id, "secure_keyguard_present");
                return;
            }
            if (WakeStatePolicy.readyToCapture(power.isInteractive(), keyguardLocked)) {
                captureWakeAfterSettle(id, options);
                return;
            }
            if (!power.isInteractive()) {
                awaitWakeAndCapture(id, options, attempt + 1);
                return;
            }
            dispatchWakeDismissGesture(id, options);
        }, WAKE_UNLOCK_READY_DELAY_MS);
    }

    private void dispatchWakeDismissGesture(String id, ScreenshotOptions options) {
        wakeDismissGestureDispatched = true;
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        path.moveTo(width * 0.5f, height * 0.82f);
        path.lineTo(width * 0.5f, height * 0.22f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 350L))
                .build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                handler.postDelayed(
                        () -> awaitWakeAndCapture(id, options, 0),
                        WAKE_SETTLE_DELAY_MS);
            }
            @Override public void onCancelled(GestureDescription description) {
                fail(id, "wake_unlock_gesture_cancelled");
            }
        }, handler);
        if (!accepted) fail(id, "wake_unlock_gesture_rejected");
    }

    private void captureWakeAfterSettle(String id, ScreenshotOptions options) {
        handler.postDelayed(() -> {
            if (!busy || !id.equals(activeId)) return;
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (keyguard == null || power == null) { fail(id, "wake_service_missing"); return; }
            boolean keyguardLocked = keyguard.isKeyguardLocked();
            if (WakeStatePolicy.secureKeyguardBlocks(keyguardLocked, keyguard.isDeviceSecure())) {
                fail(id, "secure_keyguard_present");
                return;
            }
            if (!WakeStatePolicy.readyToCapture(power.isInteractive(), keyguardLocked)) {
                fail(id, "wake_timeout");
                return;
            }
            capture(id, options);
        }, WAKE_SETTLE_DELAY_MS);
    }

    private void registerScreenPowerReceiver() {
        if (screenPowerReceiver != null) return;
        screenPowerReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    setLockedScreenWakeLock(ScreenPowerPolicy.shouldHoldAfter(
                            ScreenPowerPolicy.Change.SCREEN_OFF));
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    setLockedScreenWakeLock(ScreenPowerPolicy.shouldHoldAfter(
                            ScreenPowerPolicy.Change.SCREEN_ON));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenPowerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterScreenPowerReceiver() {
        if (screenPowerReceiver == null) return;
        try { unregisterReceiver(screenPowerReceiver); }
        catch (IllegalArgumentException ignored) { }
        screenPowerReceiver = null;
    }

    private void updateLockedScreenWakeLock() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        setLockedScreenWakeLock(power != null
                && ScreenPowerPolicy.shouldHoldPartialWakeLock(power.isInteractive()));
    }

    private void setLockedScreenWakeLock(boolean shouldHold) {
        if (!shouldHold) { releaseLockedScreenWakeLock(); return; }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return;
        if (lockedScreenWakeLock == null) {
            lockedScreenWakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "phoneforai:locked-screen-command-poll");
            lockedScreenWakeLock.setReferenceCounted(false);
        }
        if (!lockedScreenWakeLock.isHeld()) lockedScreenWakeLock.acquire();
    }

    private void releaseLockedScreenWakeLock() {
        if (lockedScreenWakeLock != null && lockedScreenWakeLock.isHeld()) {
            lockedScreenWakeLock.release();
        }
    }

    private void launch(String id, String packageName, ScreenshotOptions options) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) { fail(id, "app_launch_intent_missing"); return; }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(intent); }
        catch (RuntimeException error) { fail(id, "app_launch_rejected"); return; }
        handler.postDelayed(() -> {
            if (!id.equals(activeId)) return;
            if (!packageName.equals(currentPackage())) { fail(id, "app_launch_not_foreground"); return; }
            capture(id, options);
        }, 2500L);
    }

    private void dump(String id, JSONObject args) {
        int maxNodes = args.has("max_nodes") ? args.optInt("max_nodes", -1) : 300;
        int maxDepth = args.has("max_depth") ? args.optInt("max_depth", -1) : 12;
        if (maxNodes < 20 || maxNodes > 400 || maxDepth < 1 || maxDepth > 20) {
            fail(id, "invalid_command_args"); return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { fail(id, "ui_tree_unavailable"); return; }
        try {
            JSONArray nodes = new JSONArray();
            boolean truncated = collect(root, 0, maxDepth, maxNodes, nodes);
            finish(id, new JSONObject().put("package_name", currentPackage())
                    .put("nodes", nodes).put("truncated", truncated));
        } catch (Exception error) { fail(id, "result_encode_failed"); }
        finally { root.recycle(); }
    }

    private boolean collect(AccessibilityNodeInfo node, int depth, int maxDepth,
            int maxNodes, JSONArray out) throws Exception {
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        boolean useful = nonempty(text) || nonempty(desc) || node.isClickable() || node.isEditable()
                || node.isScrollable() || node.isCheckable();
        if (useful) {
            if (out.length() >= maxNodes) return true;
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            JSONObject item = new JSONObject();
            if (nonempty(text)) item.put("text", bounded(text));
            if (nonempty(desc)) item.put("desc", bounded(desc));
            if (node.getViewIdResourceName() != null) item.put("id", bounded(node.getViewIdResourceName()));
            if (node.getClassName() != null) item.put("class", bounded(node.getClassName()));
            item.put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
                    .put("clickable", node.isClickable()).put("editable", node.isEditable())
                    .put("focused", node.isFocused()).put("scrollable", node.isScrollable())
                    .put("checked", node.isChecked()).put("enabled", node.isEnabled()).put("depth", depth);
            out.put(item);
        }
        if (depth >= maxDepth) return false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (collect(child, depth + 1, maxDepth, maxNodes, out)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }
    private static boolean nonempty(CharSequence value) { return value != null && value.length() > 0; }
    private static String bounded(CharSequence value) {
        String text = value.toString(); return text.substring(0, Math.min(1000, text.length()));
    }

    private void setClipboard(String id, JSONObject args) {
        Object raw = args.opt("text");
        if (!(raw instanceof String) || ((String) raw).length() > 49152) {
            fail(id, "invalid_command_args"); return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) { fail(id, "clipboard_unavailable"); return; }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("phone", (String) raw));
            finish(id, new JSONObject().put("package_name", currentPackage()));
        } catch (Exception error) { fail(id, "clipboard_rejected"); }
    }

    private void type(String id, JSONObject args, ScreenshotOptions options) {
        Object raw = args.opt("text");
        if (!(raw instanceof String) || ((String) raw).length() > 49152) {
            fail(id, "invalid_command_args"); return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { fail(id, "ui_tree_unavailable"); return; }
        AccessibilityNodeInfo target = null;
        try {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null || !target.isEditable()) target = findEditable(root, 0);
            if (target == null) { fail(id, "no_editable_field"); return; }
            Bundle bundle = new Bundle();
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, (String) raw);
            if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
                fail(id, "text_input_rejected"); return;
            }
            handler.postDelayed(() -> capture(id, options), 700L);
        } finally {
            if (target != null && target != root) target.recycle();
            root.recycle();
        }
    }
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node.isEditable() && node.isEnabled()) return AccessibilityNodeInfo.obtain(node);
        if (depth >= 20) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found;
            try { found = findEditable(child, depth + 1); } finally { child.recycle(); }
            if (found != null) return found;
        }
        return null;
    }

    private void global(String id, String type, ScreenshotOptions options) {
        int action = "ui.global.back".equals(type) ? GLOBAL_ACTION_BACK
                : "ui.global.home".equals(type) ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_RECENTS;
        if (!performGlobalAction(action)) { fail(id, "global_action_rejected"); return; }
        handler.postDelayed(() -> capture(id, options), 1000L);
    }

    private void gesture(String id, String type, JSONObject args, ScreenshotOptions options) {
        int x1, y1, x2, y2, duration;
        if ("ui.swipe".equals(type)) {
            x1 = args.optInt("x1", -1); y1 = args.optInt("y1", -1);
            x2 = args.optInt("x2", -1); y2 = args.optInt("y2", -1);
            duration = args.has("duration_ms") ? args.optInt("duration_ms", -1) : 450;
            if (!inside(x1, y1) || !inside(x2, y2) || duration < 100 || duration > 3000) {
                fail(id, "invalid_command_args"); return;
            }
        } else {
            x1 = x2 = args.optInt("x", -1); y1 = y2 = args.optInt("y", -1);
            duration = "ui.long_press".equals(type)
                    ? (args.has("duration_ms") ? args.optInt("duration_ms", -1) : 650) : 90;
            if (!inside(x1, y1) || ("ui.long_press".equals(type) && (duration < 300 || duration > 3000))) {
                fail(id, "invalid_command_args"); return;
            }
        }
        Path path = new Path(); path.moveTo(x1, y1); if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, duration)).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                handler.postDelayed(() -> capture(id, options), 900L);
            }
            @Override public void onCancelled(GestureDescription description) { fail(id, "gesture_cancelled"); }
        }, handler);
        if (!accepted) fail(id, "gesture_rejected");
    }
    private boolean inside(int x, int y) { return x >= 0 && y >= 0 && x < basisWidth && y < basisHeight; }

    private void capture(String id, ScreenshotOptions options) {
        if (options == null) { fail(id, "screenshot_options_missing"); return; }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) { encode(id, result, options); }
            @Override public void onFailure(int errorCode) { fail(id, "screenshot_failed_" + errorCode); }
        });
    }

    private void encode(String id, ScreenshotResult screenshot, ScreenshotOptions options) {
        HardwareBuffer buffer = screenshot.getHardwareBuffer();
        Bitmap wrapped = null, scaled = null;
        try {
            ColorSpace color = screenshot.getColorSpace();
            wrapped = Bitmap.wrapHardwareBuffer(buffer, color);
            if (wrapped == null) { fail(id, "screenshot_bitmap_unavailable"); return; }
            int displayWidth = wrapped.getWidth(), displayHeight = wrapped.getHeight();
            int imageWidth = Math.min(displayWidth, options.maxWidth);
            int imageHeight = Math.max(1, Math.round(displayHeight * (imageWidth / (float) displayWidth)));
            scaled = imageWidth == displayWidth ? wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    : Bitmap.createScaledBitmap(wrapped, imageWidth, imageHeight, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, options.quality, output)) {
                fail(id, "screenshot_encode_failed"); return;
            }
            byte[] jpeg = output.toByteArray();
            if (jpeg.length > 3 * 1024 * 1024) { fail(id, "screenshot_too_large"); return; }
            String packageName = currentPackage();
            JSONObject metadata = new JSONObject().put("package_name", packageName)
                    .put("image_width", scaled.getWidth()).put("image_height", scaled.getHeight())
                    .put("display_width", displayWidth).put("display_height", displayHeight);
            int finalDisplayWidth = displayWidth, finalDisplayHeight = displayHeight;
            BridgeClient.postScreenshot(this, id, metadata, jpeg, uploaded -> handler.post(() -> {
                if (uploaded) {
                    basisId = id; basisPackage = packageName; basisWidth = finalDisplayWidth;
                    basisHeight = finalDisplayHeight; basisCapturedAt = SystemClock.elapsedRealtime();
                    basisValid = true;
                }
                completeLocal(id);
            }));
        } catch (Exception error) { fail(id, "screenshot_encode_failed"); }
        finally {
            if (scaled != null) scaled.recycle();
            if (wrapped != null) wrapped.recycle();
            buffer.close();
        }
    }

    private String currentPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try {
                if (root.getPackageName() != null) {
                    String value = root.getPackageName().toString();
                    if (PACKAGE.matcher(value).matches()) foregroundPackage = value;
                }
            } finally { root.recycle(); }
        }
        return foregroundPackage;
    }
    private void finish(String id, JSONObject result) { BridgeClient.postData(this, id, result); completeLocal(id); }
    private void fail(String id, String error) {
        if (ID.matcher(id == null ? "" : id).matches()) BridgeClient.postFailure(this, id, error);
        completeLocal(id);
    }
    private void completeLocal(String id) {
        if (id != null && id.equals(activeId)) { busy = false; activeId = ""; }
    }
}
