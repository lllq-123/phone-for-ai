package dev.phoneforai.companion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WakeStatePolicyTest {
    @Test
    public void capturesOnlyAfterTheDisplayIsInteractiveAndKeyguardIsGone() {
        assertTrue(WakeStatePolicy.readyToCapture(true, false));
        assertFalse(WakeStatePolicy.readyToCapture(false, false));
        assertFalse(WakeStatePolicy.readyToCapture(true, true));
    }

    @Test
    public void secureKeyguardBlocksWakeWhileSwipeKeyguardUsesOneGesture() {
        assertTrue(WakeStatePolicy.secureKeyguardBlocks(true, true));
        assertFalse(WakeStatePolicy.secureKeyguardBlocks(true, false));
        assertTrue(WakeStatePolicy.shouldDispatchUnlockGesture(true, true, false));
        assertFalse(WakeStatePolicy.shouldDispatchUnlockGesture(true, true, true));
        assertFalse(WakeStatePolicy.shouldDispatchUnlockGesture(false, true, false));
    }
}
