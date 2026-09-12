package dev.phoneforai.companion;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public final class WakeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
    }
    @Override protected void onResume() {
        super.onResume();
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || keyguard.isDeviceSecure() || !keyguard.isKeyguardLocked()) {
            handler.postDelayed(this::finishQuietly, 150L);
            return;
        }
        keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { handler.postDelayed(WakeActivity.this::finishQuietly, 150L); }
            @Override public void onDismissCancelled() { finishQuietly(); }
            @Override public void onDismissError() { finishQuietly(); }
        });
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    private void finishQuietly() { if (!isFinishing()) { finish(); overridePendingTransition(0, 0); } }
}
