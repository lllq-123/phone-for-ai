package dev.phoneforai.companion;

/** Queued managed launches are invalidated; already running launches must drain before ACK. */
final class AutomationUiBarrier {
    private long generation;
    private int running;
    private boolean blocked;

    synchronized long token() { return generation; }
    synchronized boolean permits(long token) { return !blocked && token == generation; }
    synchronized boolean begin(long token) {
        if (blocked || token != generation) return false;
        running++;
        return true;
    }
    synchronized void end() { running--; }
    synchronized void takeControl() { blocked = true; generation++; }
    synchronized void releaseControl() { blocked = false; }
    synchronized boolean idle() { return running == 0; }
}
