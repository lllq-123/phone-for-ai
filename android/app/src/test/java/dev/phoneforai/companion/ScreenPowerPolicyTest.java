package dev.phoneforai.companion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ScreenPowerPolicyTest {
    @Test
    public void holdsPartialWakeLockOnlyWhileTheScreenSleeps() {
        assertTrue(ScreenPowerPolicy.shouldHoldPartialWakeLock(false));
        assertFalse(ScreenPowerPolicy.shouldHoldPartialWakeLock(true));
    }

    @Test
    public void screenBroadcastsMapDirectlyToAcquireAndRelease() {
        assertTrue(ScreenPowerPolicy.shouldHoldAfter(ScreenPowerPolicy.Change.SCREEN_OFF));
        assertFalse(ScreenPowerPolicy.shouldHoldAfter(ScreenPowerPolicy.Change.SCREEN_ON));
    }
}
