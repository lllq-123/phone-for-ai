package dev.phoneforai.companion;

final class HeartbeatTiming {
    private HeartbeatTiming() {}
    static long successDelayMillis(Object longPoll, Object interval) {
        if (positive(longPoll, 1, Integer.MAX_VALUE) != null) return 0L;
        Integer seconds = positive(interval, 1, 60);
        return 1000L * (seconds == null ? 2 : seconds);
    }
    private static Integer positive(Object raw, int min, int max) {
        if (!(raw instanceof Number)) return null;
        Number value = (Number) raw;
        long number = value.longValue();
        return number >= min && number <= max && value.doubleValue() == (double) number
                ? (int) number : null;
    }
}
