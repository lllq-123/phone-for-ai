package dev.phoneforai.companion;

final class ScreenshotOptions {
    static final ScreenshotOptions CAPTURE = new ScreenshotOptions(1080, 72);
    static final ScreenshotOptions ACTION = new ScreenshotOptions(720, 60);
    final int maxWidth;
    final int quality;
    private ScreenshotOptions(int maxWidth, int quality) {
        this.maxWidth = maxWidth;
        this.quality = quality;
    }
    static ScreenshotOptions forCommand(String type, Object width, Object quality) {
        if ("screen.capture".equals(type)) return resolve(width, quality, CAPTURE);
        if ("screen.wake".equals(type) || "app.launch".equals(type)
                || type.startsWith("ui.tap") || type.startsWith("ui.long_press")
                || type.startsWith("ui.swipe") || type.startsWith("ui.type")
                || type.startsWith("ui.global.")) return resolve(width, quality, ACTION);
        return null;
    }
    private static ScreenshotOptions resolve(Object width, Object quality, ScreenshotOptions defaults) {
        return new ScreenshotOptions(number(width, defaults.maxWidth, 540, 1440),
                number(quality, defaults.quality, 50, 90));
    }
    private static int number(Object raw, int fallback, int min, int max) {
        if (raw == null) return fallback;
        if (!(raw instanceof Number)) throw new IllegalArgumentException("invalid_number");
        Number value = (Number) raw;
        long n = value.longValue();
        if (n < min || n > max || value.doubleValue() != (double) n)
            throw new IllegalArgumentException("invalid_number");
        return (int) n;
    }
}
