package dev.phoneforai.companion;

/** A one-shot wake sequence. Host callbacks must be delivered on the same event loop. */
public final class ScreenWakeCoordinator {
    static final long POLL_INTERVAL_MS = 150L;
    static final long SWIPE_LOCK_SETTLE_MS = 600L;
    static final long TIMEOUT_MS = 6_000L;

    public interface Host {
        /** Monotonic time, such as elapsedRealtime on Android. */
        long nowMs();
        /** Whether this coordinator still belongs to the active task. */
        boolean isCurrent();
        State state();
        boolean launchWakeActivity();
        boolean dismissSwipe(Runnable completed, Runnable cancelled);
        void postDelayed(Runnable action, long delayMs);
        void trace(String phase, State state);
    }

    public static final class State {
        public final boolean available;
        public final boolean interactive;
        public final boolean displayOn;
        public final boolean locked;
        public final boolean secure;

        public State(boolean available, boolean interactive, boolean displayOn,
                boolean locked, boolean secure) {
            this.available = available;
            this.interactive = interactive;
            this.displayOn = displayOn;
            this.locked = locked;
            this.secure = secure;
        }
    }

    public interface Callback {
        void ready();
        void failed(String reason);
    }

    private final Host host;
    private final Callback callback;
    private boolean started;
    private volatile boolean terminal;
    private boolean swipeDispatched;
    private boolean lockSettlingTraced;
    private long startMs;
    private long swipeLockSinceMs = -1L;

    public ScreenWakeCoordinator(Host host, Callback callback) {
        if (host == null || callback == null) {
            throw new IllegalArgumentException("host and callback are required");
        }
        this.host = host;
        this.callback = callback;
    }

    public void start() {
        if (started || terminal) return;
        started = true;
        startMs = host.nowMs();
        if (!active()) return;

        State state = host.state();
        host.trace("wake_start", state);
        if (evaluate(state, false)) return;

        // Only one activity launch is allowed, including when the display is already on
        // but a non-secure swipe lock remains.
        if (!active()) return;
        if (!host.launchWakeActivity()) {
            fail("wake_activity_rejected", state);
            return;
        }
        host.trace("wake_activity_requested", state);
        schedulePoll();
    }

    /** Silently invalidate this attempt, including all previously posted callbacks. */
    public void cancel() {
        terminal = true;
    }

    private boolean active() {
        return started && !terminal && host.isCurrent();
    }

    private boolean timedOut() {
        return host.nowMs() - startMs >= TIMEOUT_MS;
    }

    private boolean evaluate(State state, boolean maySwipe) {
        if (!active()) return true;
        if (timedOut()) {
            fail("wake_timeout", state);
            return true;
        }
        if (state == null || !state.available) {
            fail("wake_service_missing", state);
            return true;
        }
        if (state.locked && state.secure) {
            fail("secure_keyguard_present", state);
            return true;
        }
        if (state.displayOn && state.interactive && !state.locked) {
            swipeLockSinceMs = -1L;
            terminal = true;
            host.trace("wake_ready", state);
            callback.ready();
            return true;
        }
        if (!state.displayOn || !state.interactive || !state.locked) {
            swipeLockSinceMs = -1L;
            return false;
        }
        if (swipeLockSinceMs < 0L) {
            swipeLockSinceMs = host.nowMs();
            if (!lockSettlingTraced) {
                lockSettlingTraced = true;
                host.trace("wake_lock_settling", state);
            }
        }
        if (maySwipe && !swipeDispatched
                && host.nowMs() - swipeLockSinceMs >= SWIPE_LOCK_SETTLE_MS) {
            swipeDispatched = true;
            host.trace("wake_dismiss_requested", state);
            if (!host.dismissSwipe(this::onSwipeCompleted, this::onSwipeCancelled)) {
                fail("wake_dismiss_gesture_rejected", state);
                return true;
            }
        }
        return terminal;
    }

    private void schedulePoll() {
        if (!active()) return;
        long remaining = TIMEOUT_MS - (host.nowMs() - startMs);
        if (remaining <= 0L) {
            fail("wake_timeout", null);
            return;
        }
        host.postDelayed(this::poll, Math.min(POLL_INTERVAL_MS, remaining));
    }

    private void poll() {
        if (!active()) return;
        if (timedOut()) {
            fail("wake_timeout", null);
            return;
        }
        if (!evaluate(host.state(), true)) schedulePoll();
    }

    private void onSwipeCompleted() {
        if (!active()) return;
        if (timedOut()) {
            fail("wake_timeout", null);
            return;
        }
        // The gesture callback only says input finished. Read actual display/keyguard
        // state before reporting readiness; the existing poll covers a delayed unlock.
        evaluate(host.state(), true);
    }

    private void onSwipeCancelled() {
        if (!active()) return;
        if (timedOut()) {
            fail("wake_timeout", null);
            return;
        }
        fail("wake_dismiss_gesture_cancelled", host.state());
    }

    private void fail(String reason, State state) {
        if (!active()) return;
        terminal = true;
        host.trace(reason, state);
        callback.failed(reason);
    }
}
