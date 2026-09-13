package dev.phoneforai.companion;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public final class WakeActivity extends Activity {
    private static final long MAX_LIFETIME_MS = 2_500L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dismissRequested;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        // Some OEM permission dialogs can cover this activity without completing
        // the keyguard callback. Never leave that stale wake instance reusable.
        handler.postDelayed(this::finishQuietly, MAX_LIFETIME_MS);
    }
    @Override protected void onResume() {
        super.onResume();
        if (dismissRequested) return;
        dismissRequested = true;
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || keyguard.isDeviceSecure()) {
            finishQuietly();
            return;
        }
        if (!keyguard.isKeyguardLocked()) {
            handler.postDelayed(this::finishQuietly, 120L);
            return;
        }
        keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { handler.postDelayed(WakeActivity.this::finishQuietly, 120L); }
            @Override public void onDismissCancelled() { finishQuietly(); }
            @Override public void onDismissError() { finishQuietly(); }
        });
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    private void finishQuietly() { if (!isFinishing()) { finish(); overridePendingTransition(0, 0); } }
}
