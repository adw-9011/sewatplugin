package io.github.near.sweats.ram;

import java.util.Locale;

public final class RamFormat {

    private static final double MEGABYTE = 1024.0D * 1024.0D;
    private static final double GIGABYTE = 1024.0D * MEGABYTE;

    private RamFormat() {}

    public static String bytes(final long bytes) {
        final long safe = Math.max(0L, bytes);
        if (safe >= GIGABYTE) {
            return String.format(Locale.ROOT, "%.2f GB", safe / GIGABYTE);
        }
        return String.format(Locale.ROOT, "%.1f MB", safe / MEGABYTE);
    }

    public static String percent(final RamStats stats) {
        return stats.hasMaximum()
                ? String.format(Locale.ROOT, "%.1f%%", stats.usedPercent())
                : "n/a";
    }
}