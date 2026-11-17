package dev.phoneforai.companion;

/** Read-only commands remain available while a person controls the screen. */
final class PhoneCommandPolicy {
    private PhoneCommandPolicy() {}
    static boolean mutates(String type) {
        return !("screen.capture".equals(type) || "ui.dump".equals(type)
                || "files.list".equals(type) || "files.read".equals(type)
                || "apps.list".equals(type));
    }
}
