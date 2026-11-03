package com.personal.sonyvegasvideo;

/** Pure-Java helpers so trim-boundary logic can be verified off-device. */
public final class ClipMath {
    private ClipMath() { }

    public static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    public static boolean validRange(long startMillis, long endMillis) {
        return startMillis >= 0 && endMillis > startMillis;
    }

    public static long sampleTimestamp(long sampleMicros, long firstSampleMicros) {
        return Math.max(0L, sampleMicros - firstSampleMicros);
    }

    public static int progress(long position, long start, long end) {
        if (end <= start || position <= start) return 0;
        if (position >= end) return 100;
        return (int) (100.0 * (position - start) / (end - start));
    }

    public static String clock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds / 60) % 60;
        long remainder = seconds % 60;
        return (hours > 0 ? hours + ":" + two(minutes) : Long.toString(seconds / 60))
                + ":" + two(remainder);
    }

    public static String clockCode(long millis) {
        long value = Math.max(0, millis);
        long seconds = value / 1000;
        long ms = value % 1000;
        return two(seconds / 3600) + ":" + two((seconds / 60) % 60) + ":" + two(seconds % 60)
                + "." + (ms < 100 ? "0" : "") + (ms < 10 ? "0" : "") + ms;
    }

    public static int parseClock(String value) {
        String[] parts = value.trim().split(":", -1);
        if (parts.length < 1 || parts.length > 3) throw new IllegalArgumentException("Формат: ЧЧ:ММ:СС.мс");
        double seconds = 0;
        for (int i = 0; i < parts.length; i++) {
            double part = Double.parseDouble(parts[i].replace(',', '.'));
            if (!Double.isFinite(part) || part < 0 || (i > 0 && part >= 60)
                    || (i < parts.length - 1 && part != Math.floor(part)))
                throw new IllegalArgumentException("Некорректное время");
            seconds = seconds * 60 + part;
        }
        if (seconds * 1000 > Integer.MAX_VALUE) throw new IllegalArgumentException("Слишком большое время");
        return (int) Math.round(seconds * 1000);
    }

    private static String two(long value) { return value < 10 ? "0" + value : Long.toString(value); }
}
