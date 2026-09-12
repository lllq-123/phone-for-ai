package dev.phoneforai.companion;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class HeartbeatTimingTest {
    @Test public void longPollDoesNotAddASecondDelay() {
        assertEquals(0L, HeartbeatTiming.successDelayMillis(20, 2));
    }
    @Test public void oldServerResponseUsesBoundedInterval() {
        assertEquals(2000L, HeartbeatTiming.successDelayMillis(null, null));
        assertEquals(60000L, HeartbeatTiming.successDelayMillis(0, 60));
        assertEquals(2000L, HeartbeatTiming.successDelayMillis(0, 61));
    }
}
