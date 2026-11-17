package dev.phoneforai.companion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AutomationUiBarrierTest {
    @Test public void queuedLaunchCannotRunAfterTakeoverOrAfterLaterRelease() {
        AutomationUiBarrier barrier = new AutomationUiBarrier();
        long queued = barrier.token();
        assertTrue(barrier.permits(queued));
        barrier.takeControl();
        assertFalse(barrier.permits(queued));
        assertFalse(barrier.begin(queued));
        assertFalse(barrier.begin(barrier.token()));
        barrier.releaseControl();
        assertFalse(barrier.permits(queued));
        assertTrue(barrier.permits(barrier.token()));
        assertFalse(barrier.begin(queued));
        assertTrue(barrier.begin(barrier.token()));
        barrier.end();
        assertTrue(barrier.idle());
    }

    @Test public void runningLaunchMustDrainAndCannotBeReplacedWhileUserOwnsUi() {
        AutomationUiBarrier barrier = new AutomationUiBarrier();
        assertTrue(barrier.begin(barrier.token()));
        barrier.takeControl();
        assertFalse(barrier.idle());
        assertFalse(barrier.begin(barrier.token()));
        barrier.end();
        assertTrue(barrier.idle());
    }
}
