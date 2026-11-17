package dev.phoneforai.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ScreenWakeCoordinatorTest {
    private static final ScreenWakeCoordinator.State DARK = state(true, false, false, true, false);
    private static final ScreenWakeCoordinator.State READY = state(true, true, true, false, false);
    private static final ScreenWakeCoordinator.State SWIPE_LOCK = state(true, true, true, true, false);

    @Test
    public void darkScreenLaunchesOnceAndOnlyReadyAfterObservedUnlock() {
        FakeHost host = new FakeHost(DARK);
        Result result = start(host);
        assertEquals(1, host.launches);
        host.advanceTo(150);
        assertEquals(0, result.readyCount);
        host.state = READY;
        host.advanceTo(300);
        assertEquals(1, result.readyCount);
        assertNull(result.failure);
        host.advanceTo(10_000);
        assertEquals(1, result.readyCount);
    }

    @Test
    public void swipeLockDispatchesOnceAndRequiresObservedUnlock() {
        FakeHost host = new FakeHost(SWIPE_LOCK);
        Result result = start(host);
        assertEquals(1, host.launches);
        assertEquals(0, host.swipes);
        host.advanceTo(599);
        assertEquals(0, host.swipes);
        host.advanceTo(600);
        assertEquals(1, host.swipes);
        host.completed.run();
        assertEquals(0, result.readyCount);
        host.advanceTo(900);
        assertEquals(1, host.swipes);
        host.state = READY;
        host.completed.run();
        assertEquals(1, result.readyCount);
        host.advanceTo(1_000);
        assertEquals(1, result.readyCount);
    }

    @Test
    public void darkScreenWaitsSixHundredMsFromFirstObservedSwipeLock() {
        FakeHost host = new FakeHost(DARK);
        Result result = start(host);
        host.advanceTo(300);
        host.state = SWIPE_LOCK;
        host.advanceTo(450); // First observed at 450, not at launch time.
        assertEquals(0, host.swipes);
        host.advanceTo(1_049);
        assertEquals(0, host.swipes);
        host.advanceTo(1_050);
        assertEquals(1, host.swipes);
        assertEquals(1, Collections.frequency(host.traces, "wake_lock_settling"));
        assertEquals(0, result.readyCount);
    }

    @Test
    public void displayAndInteractiveJitterRestartSwipeLockSettling() {
        FakeHost host = new FakeHost(SWIPE_LOCK);
        start(host);
        host.advanceTo(300);
        host.state = state(true, true, false, true, false);
        host.advanceTo(450); // Display went off: previous 300 ms do not count.
        host.state = SWIPE_LOCK;
        host.advanceTo(600);
        host.advanceTo(1_050);
        assertEquals(0, host.swipes);
        host.state = state(true, false, true, true, false);
        host.advanceTo(1_200); // Interactive state also resets the timer.
        host.state = SWIPE_LOCK;
        host.advanceTo(1_350);
        host.advanceTo(1_800);
        assertEquals(0, host.swipes);
        host.advanceTo(1_950);
        assertEquals(1, host.swipes);
    }

    @Test
    public void observedUnlockIsReadyWithoutWaitingForSwipeSettling() {
        FakeHost host = new FakeHost(SWIPE_LOCK);
        Result result = start(host);
        host.advanceTo(150);
        host.state = READY;
        host.advanceTo(300);
        assertEquals(1, result.readyCount);
        assertEquals(0, host.swipes);
    }

    @Test
    public void secureLockAndMissingServicesFailBeforeLaunch() {
        FakeHost secure = new FakeHost(state(true, true, true, true, true));
        assertEquals("secure_keyguard_present", start(secure).failure);
        assertEquals(0, secure.launches);

        FakeHost missing = new FakeHost(state(false, false, false, true, false));
        assertEquals("wake_service_missing", start(missing).failure);
        assertEquals(0, missing.launches);
    }

    @Test
    public void interactiveButDisplayOffCannotBeReady() {
        FakeHost host = new FakeHost(state(true, true, false, false, false));
        Result result = start(host);
        host.advanceTo(300);
        assertEquals(0, result.readyCount);
        host.state = READY;
        host.advanceTo(450);
        assertEquals(1, result.readyCount);
    }

    @Test
    public void unresolvedWakeTimesOutAtAbsoluteDeadline() {
        FakeHost host = new FakeHost(DARK);
        Result result = start(host);
        host.advanceTo(5_999);
        assertNull(result.failure);
        host.advanceTo(6_000);
        assertEquals("wake_timeout", result.failure);
        assertEquals(1, result.failCount);
        host.state = READY;
        host.advanceTo(10_000);
        assertEquals(0, result.readyCount);
    }

    @Test
    public void launchAndGestureRejectionOrCancellationHaveSpecificReasons() {
        FakeHost launch = new FakeHost(DARK);
        launch.acceptLaunch = false;
        assertEquals("wake_activity_rejected", start(launch).failure);

        FakeHost rejected = new FakeHost(SWIPE_LOCK);
        rejected.acceptSwipe = false;
        Result rejectedResult = start(rejected);
        rejected.advanceTo(600);
        assertEquals("wake_dismiss_gesture_rejected", rejectedResult.failure);

        FakeHost cancelled = new FakeHost(SWIPE_LOCK);
        Result cancelledResult = start(cancelled);
        cancelled.advanceTo(600);
        cancelled.cancelled.run();
        assertEquals("wake_dismiss_gesture_cancelled", cancelledResult.failure);
    }

    @Test
    public void cancelAndExpiredIdentitySuppressOldPollsAndGestures() {
        FakeHost cancelled = new FakeHost(SWIPE_LOCK);
        Result cancelledResult = new Result();
        ScreenWakeCoordinator coordinator = new ScreenWakeCoordinator(cancelled, cancelledResult);
        coordinator.start();
        cancelled.advanceTo(600);
        coordinator.cancel();
        cancelled.state = READY;
        cancelled.completed.run();
        cancelled.advanceTo(10_000);
        assertEquals(0, cancelledResult.readyCount + cancelledResult.failCount);

        FakeHost expired = new FakeHost(SWIPE_LOCK);
        Result expiredResult = start(expired);
        expired.advanceTo(600);
        expired.current = false;
        expired.state = READY;
        expired.completed.run();
        expired.advanceTo(10_000);
        assertEquals(0, expiredResult.readyCount + expiredResult.failCount);
    }

    @Test
    public void lateGestureCannotExtendDeadlineOrCompleteOldAttempt() {
        FakeHost host = new FakeHost(SWIPE_LOCK);
        Result result = start(host);
        host.advanceTo(600);
        host.advanceTo(6_000);
        host.state = READY;
        host.completed.run();
        host.cancelled.run();
        assertEquals("wake_timeout", result.failure);
        assertEquals(1, result.failCount);
        assertEquals(0, result.readyCount);
    }

    @Test
    public void settlingAndLateGestureNeverExtendAbsoluteDeadline() {
        FakeHost host = new FakeHost(DARK);
        Result result = start(host);
        host.advanceTo(5_700);
        host.state = SWIPE_LOCK;
        host.advanceTo(5_850);
        assertEquals(0, host.swipes);
        host.advanceTo(6_000);
        assertEquals("wake_timeout", result.failure);
        assertEquals(0, host.swipes);
    }

    private static ScreenWakeCoordinator.State state(boolean available, boolean interactive,
            boolean displayOn, boolean locked, boolean secure) {
        return new ScreenWakeCoordinator.State(available, interactive, displayOn, locked, secure);
    }

    private static Result start(FakeHost host) {
        Result result = new Result();
        new ScreenWakeCoordinator(host, result).start();
        return result;
    }

    private static final class Result implements ScreenWakeCoordinator.Callback {
        int readyCount;
        int failCount;
        String failure;

        @Override public void ready() { readyCount++; }
        @Override public void failed(String reason) { failCount++; failure = reason; }
    }

    private static final class FakeHost implements ScreenWakeCoordinator.Host {
        final List<Scheduled> scheduled = new ArrayList<>();
        final List<String> traces = new ArrayList<>();
        ScreenWakeCoordinator.State state;
        long now;
        boolean current = true;
        boolean acceptLaunch = true;
        boolean acceptSwipe = true;
        int launches;
        int swipes;
        Runnable completed;
        Runnable cancelled;

        FakeHost(ScreenWakeCoordinator.State state) { this.state = state; }

        @Override public long nowMs() { return now; }
        @Override public boolean isCurrent() { return current; }
        @Override public ScreenWakeCoordinator.State state() { return state; }
        @Override public boolean launchWakeActivity() { launches++; return acceptLaunch; }
        @Override public boolean dismissSwipe(Runnable completed, Runnable cancelled) {
            swipes++;
            this.completed = completed;
            this.cancelled = cancelled;
            return acceptSwipe;
        }
        @Override public void postDelayed(Runnable action, long delayMs) {
            scheduled.add(new Scheduled(now + delayMs, action));
        }
        @Override public void trace(String phase, ScreenWakeCoordinator.State state) { traces.add(phase); }

        void advanceTo(long target) {
            while (true) {
                int first = -1;
                for (int i = 0; i < scheduled.size(); i++) {
                    if (scheduled.get(i).at <= target &&
                            (first < 0 || scheduled.get(i).at < scheduled.get(first).at)) first = i;
                }
                if (first < 0) break;
                Scheduled next = scheduled.remove(first);
                now = next.at;
                next.action.run();
            }
            now = target;
        }
    }

    private static final class Scheduled {
        final long at;
        final Runnable action;
        Scheduled(long at, Runnable action) { this.at = at; this.action = action; }
    }
}
