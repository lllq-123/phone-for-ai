package dev.phoneforai.companion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScreenStreamControlPolicyTest {
    @Test public void observationDoesNotOwnUiUntilAutomationIsQuiescent() {
        ScreenStreamControlPolicy policy = new ScreenStreamControlPolicy(true);
        assertFalse(policy.isControlActive());
        assertFalse(policy.acceptsInput());
        int[] canceled = {0};
        assertTrue(policy.takeControl(() -> {
            assertTrue(policy.isControlActive());
            assertFalse(policy.acceptsInput());
            canceled[0]++;
        }));
        assertTrue(policy.acceptsInput());
        assertEquals(1, canceled[0]);
        // A duplicated handshake does not cancel a second task or disturb the UI.
        assertTrue(policy.takeControl(() -> canceled[0]++));
        assertEquals(1, canceled[0]);
    }

    @Test public void failedQuiescenceCannotAdmitTheBufferedFirstInput() {
        ScreenStreamControlPolicy policy = new ScreenStreamControlPolicy(true);
        try {
            policy.takeControl(() -> { throw new IllegalStateException("cancel failed"); });
            fail("expected cancellation failure");
        } catch (IllegalStateException expected) {
            assertTrue(policy.isControlActive());
            assertFalse(policy.acceptsInput());
        }
        policy.stop();
        assertFalse(policy.isControlActive());
    }

    @Test public void firstInputWaitsForNativeGestureAndCancelsOnlyOnce() {
        ScreenStreamControlPolicy policy = new ScreenStreamControlPolicy(true);
        int[] canceled = {0};
        assertFalse(policy.takeControl(() -> canceled[0]++, () -> false));
        assertTrue(policy.isControlActive());
        assertFalse(policy.acceptsInput());
        assertFalse(policy.takeControl(() -> canceled[0]++, () -> false));
        assertTrue(policy.takeControl(() -> canceled[0]++, () -> true));
        assertEquals(1, canceled[0]);
        assertTrue(policy.acceptsInput());
    }

    @Test public void closingDuringTakeoverNeverReadiesOrReacquiresOwnership() {
        ScreenStreamControlPolicy policy = new ScreenStreamControlPolicy(true);
        assertFalse(policy.takeControl(policy::stop));
        assertFalse(policy.acceptsInput());
        assertFalse(policy.isControlActive());
        assertFalse(policy.takeControl(() -> fail("closed streams cannot cancel tasks")));
    }

    @Test public void legacyStreamsRetainControlAndDisconnectAlwaysReleasesIt() {
        ScreenStreamControlPolicy policy = new ScreenStreamControlPolicy(false);
        assertTrue(policy.isControlActive());
        assertTrue(policy.acceptsInput());
        assertTrue(policy.takeControl(() -> fail("legacy stream already owns UI")));
        policy.stop();
        assertFalse(policy.isControlActive());
        assertFalse(policy.acceptsInput());
    }
}
