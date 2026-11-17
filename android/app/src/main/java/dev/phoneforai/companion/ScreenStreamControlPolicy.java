package dev.phoneforai.companion;

/** Stream lifetime and UI ownership are independent. Transitions run on the UI loop. */
final class ScreenStreamControlPolicy {
    final boolean observe;
    private volatile boolean controlActive;
    private volatile boolean ready;
    private volatile boolean stopped;
    private boolean quiesced;

    ScreenStreamControlPolicy(boolean observe) {
        this.observe = observe;
        controlActive = ready = !observe;
    }

    boolean isControlActive() { return !stopped && controlActive; }
    boolean acceptsInput() { return isControlActive() && ready; }

    synchronized boolean takeControl(Runnable quiesce) {
        return takeControl(quiesce, () -> true);
    }

    synchronized boolean takeControl(Runnable quiesce, java.util.function.BooleanSupplier gesturesIdle) {
        if (stopped) return false;
        if (ready) return true;
        // New automation must see ownership before any cancellation can finish a task.
        controlActive = true;
        if (!quiesced) {
            quiesce.run();
            quiesced = true;
        }
        if (stopped) return false;
        if (!gesturesIdle.getAsBoolean()) return false;
        ready = true;
        return true;
    }

    void stop() { stopped = true; controlActive = ready = false; }
}
