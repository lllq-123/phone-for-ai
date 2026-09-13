package dev.phoneforai.companion;

/** Pure wake/keyguard decisions shared by the Android wake command and unit tests. */
final class WakeStatePolicy {
    private WakeStatePolicy() {}

    static boolean secureKeyguardBlocks(boolean keyguardLocked, boolean deviceSecure) {
        return keyguardLocked && deviceSecure;
    }

    static boolean readyToCapture(boolean interactive, boolean keyguardLocked) {
        return interactive && !keyguardLocked;
    }

    static boolean shouldDispatchUnlockGesture(
            boolean interactive,
            boolean keyguardLocked,
            boolean gestureAlreadyDispatched
    ) {
        return interactive && keyguardLocked && !gestureAlreadyDispatched;
    }
}
