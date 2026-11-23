package io.github.near.sweats.moderation;

public final class DurationFormat {

    private DurationFormat() {}

    public static String human(final long millis) {
        final long totalSeconds = Math.max(0L, millis) / 1000L;
        final long days = totalSeconds / 86_400L;
        final long hours = totalSeconds % 86_400L / 3_600L;
        final long minutes = totalSeconds % 3_600L / 60L;
        final long seconds = totalSeconds % 60L;

        if (days > 0L) {
            return withUnits(days, "d", hours, "h", minutes, "m");
        }
        if (hours > 0L) {
            return withUnits(hours, "h", minutes, "m", 0L, "");
        }
        if (minutes > 0L) {
            return withUnits(minutes, "m", seconds, "s", 0L, "");
        }
        return seconds + "s";
    }

    public static String remaining(final Punishment punishment, final long now) {
        if (punishment.permanent()) {
            return "Permanent";
        }
        return human(Math.max(0L, punishment.expiresAt() - now));
    }

    private static String withUnits(
            final long first, final String firstUnit,
            final long second, final String secondUnit,
            final long third, final String thirdUnit) {
        final StringBuilder out = new StringBuilder();
        append(out, first, firstUnit);
        append(out, second, secondUnit);
        append(out, third, thirdUnit);
        return out.toString();
    }

    private static void append(final StringBuilder out, final long value, final String unit) {
        if (value <= 0L || unit.isEmpty()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(value).append(unit);
    }
}
