package dev.phoneforai.companion;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One serial long-poll loop. Failed polls back off and never busy-spin. */
final class HeartbeatPoller {
    interface Request {
        boolean execute() throws Exception;
        long successDelayMillis();
        void cancel();
    }
    interface Factory { Request create(); }

    private final ScheduledExecutorService executor;
    private final Factory factory;
    private boolean started;
    private boolean again;
    private long generation;
    private int failures;
    private ScheduledFuture<?> scheduled;
    private Request current;

    HeartbeatPoller(ScheduledExecutorService executor, Factory factory) {
        this.executor = executor;
        this.factory = factory;
    }

    synchronized void start() {
        if (started) return;
        started = true;
        failures = 0;
        generation++;
        schedule(generation, 1000L);
    }

    void stop() {
        Request request;
        synchronized (this) {
            started = false;
            again = false;
            generation++;
            if (scheduled != null) scheduled.cancel(false);
            scheduled = null;
            request = current;
        }
        if (request != null) request.cancel();
    }

    synchronized void requestNow() {
        if (!started) return;
        if (current != null) { again = true; return; }
        generation++;
        if (scheduled != null) scheduled.cancel(false);
        schedule(generation, 0L);
    }

    private void run(long expected) {
        Request request;
        synchronized (this) {
            if (!started || generation != expected) return;
            scheduled = null;
            current = request = factory.create();
        }
        boolean ok = false;
        try { ok = request.execute(); } catch (Exception ignored) { }
        synchronized (this) {
            if (current == request) current = null;
            if (!started || generation != expected) return;
            failures = ok ? 0 : Math.min(6, failures + 1);
            long delay = again ? 0L : nextDelay(ok, request.successDelayMillis(), failures);
            again = false;
            schedule(expected, delay);
        }
    }

    private void schedule(long expected, long delay) {
        scheduled = executor.schedule(() -> run(expected), delay, TimeUnit.MILLISECONDS);
    }

    static long nextDelay(boolean ok, long successDelay, int failures) {
        if (ok) return Math.max(0L, successDelay);
        return Math.min(30_000L, 1000L << Math.max(0, failures - 1));
    }
}
