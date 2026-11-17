package dev.phoneforai.companion;

/** Intent launches have no completion callback; keep a bounded settling window. */
final class ManagedIntentLaunchPolicy {
    static final long SETTLE_MS = 2500L;
    static final long MAX_WAIT_MS = 5000L;
    final String packageName;
    private final long startedAt;

    ManagedIntentLaunchPolicy(String packageName, long startedAt) {
        this.packageName = packageName;
        this.startedAt = startedAt;
    }

    boolean finished(long now, String foreground) {
        long elapsed = now - startedAt;
        return elapsed >= MAX_WAIT_MS
                || elapsed >= SETTLE_MS && packageName.equals(foreground);
    }
}
