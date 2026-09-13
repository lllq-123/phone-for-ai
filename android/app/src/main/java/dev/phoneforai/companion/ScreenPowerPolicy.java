package dev.phoneforai.companion;

/** Keeps command polling alive while the display sleeps. */
final class ScreenPowerPolicy {
    enum Change { SCREEN_OFF, SCREEN_ON }

    private ScreenPowerPolicy() {}

    static boolean shouldHoldPartialWakeLock(boolean screenInteractive) {
        return !screenInteractive;
    }

    static boolean shouldHoldAfter(Change change) {
        return change == Change.SCREEN_OFF;
    }
}
