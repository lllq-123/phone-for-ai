package dev.phoneforai.companion;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WakeActivity extends Activity {
    private static final long MAX_LIFETIME_MS = 2_500L;
    private static volatile boolean wakeAllowed=true;
    private static WakeActivity active;
    private static final String REQUEST="wake_request";
    private static final Set<String> PENDING=ConcurrentHashMap.newKeySet();
    static Intent prepare(Intent intent){
        String token=UUID.randomUUID().toString();PENDING.add(token);return intent.putExtra(REQUEST,token);
    }
    static void cancelLaunch(Intent intent){
        String token=intent==null?null:intent.getStringExtra(REQUEST);
        if(token!=null)PENDING.remove(token);
    }
    static void allowWake(){wakeAllowed=true;}
    static boolean controlReady(){return PENDING.isEmpty() && active==null;}
    static void suppressWake(){
        wakeAllowed=false;
        WakeActivity activity=active;
        if(activity!=null){
            activity.setTurnScreenOn(false);activity.handler.removeCallbacksAndMessages(null);activity.finishQuietly();
        }
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dismissRequested;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        cancelLaunch(getIntent());active=this;
        if(!wakeAllowed){setTurnScreenOn(false);finishQuietly();return;}
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        // Some OEM permission dialogs can cover this activity without completing
        // the keyguard callback. Never leave that stale wake instance reusable.
        handler.postDelayed(this::finishQuietly, MAX_LIFETIME_MS);
    }
    @Override protected void onResume() {
        super.onResume();
        if(!wakeAllowed){setTurnScreenOn(false);finishQuietly();return;}
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
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);cancelLaunch(intent);if(!wakeAllowed)finishQuietly();
    }
    @Override protected void onDestroy() {
        if(active==this)active=null;
        handler.removeCallbacksAndMessages(null);super.onDestroy();
    }
    private void finishQuietly() { if (!isFinishing()) { finish(); overridePendingTransition(0, 0); } }
}
