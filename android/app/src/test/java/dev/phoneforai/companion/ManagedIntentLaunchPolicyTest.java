package dev.phoneforai.companion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ManagedIntentLaunchPolicyTest {
    @Test public void silentRejectionOrSystemDialogCannotPermanentlyBlockTakeover() {
        AutomationUiBarrier barrier=new AutomationUiBarrier();
        ManagedIntentLaunchPolicy launch=new ManagedIntentLaunchPolicy("dev.target",100L);
        assertTrue(barrier.begin(barrier.token()));
        // startActivity returned normally, but the command fails its 2.5s foreground check.
        assertFalse(launch.finished(2600L,"com.android.intentresolver"));
        barrier.takeControl();
        assertFalse(barrier.idle());
        assertFalse(launch.finished(5099L,"com.android.intentresolver"));
        assertTrue(launch.finished(5100L,"com.android.intentresolver"));
        barrier.end();
        assertTrue(barrier.idle());
    }

    @Test public void confirmedTargetStillSettlesBeforeTheBarrierCanDrain() {
        ManagedIntentLaunchPolicy launch=new ManagedIntentLaunchPolicy("dev.target",100L);
        assertFalse(launch.finished(2599L,"dev.target"));
        assertTrue(launch.finished(2600L,"dev.target"));
    }

    @Test public void canceledCommandKeepsOnlyItsBoundedNativeSettlingWindow() {
        ManagedIntentLaunchPolicy launch=new ManagedIntentLaunchPolicy("dev.target",100L);
        assertFalse(launch.finished(101L,"dev.other"));
        assertFalse(launch.finished(5099L,"dev.other"));
        assertTrue(launch.finished(5100L,"dev.other"));
        assertTrue(launch.finished(10000L,"dev.other"));
    }
}
