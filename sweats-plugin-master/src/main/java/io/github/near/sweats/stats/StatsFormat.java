package io.github.near.sweats.stats;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Turns the raw numbers behind {@code /stats} into the short strings shown in the menu.
 *
 * <p>Everything here is plain Java, so the wording and rounding can be unit tested without a
 * server.
 */
public final class StatsFormat {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.UK);

    private StatsFormat() {}

    /** {@code 12,345} - grouped so long counts stay readable. */
    public static String count(final long value) {
        return decimal("#,##0").format(value);
    }

    /**
     * The movement statistics are stored in centimetres; this turns them into blocks, and into
     * kilometres once the number gets long.
     */
    public static String distance(final long centimetres) {
        if (centimetres <= 0L) {
            return "0 blocks";
        }
        final long blocks = centimetres / 100L;
        if (blocks < 1_000L) {
            return count(blocks) + " blocks";
        }
        return count(blocks) + " blocks (" + decimal("#,##0.0").format(blocks / 1000.0D) + " km)";
    }

    /** Playtime is stored in ticks; this prints it as {@code 3d 4h 12m}, {@code 4h 12m} or {@code 12s}. */
    public static String duration(final long ticks) {
        final long seconds = Math.max(0L, ticks) / 20L;
        final long days = seconds / 86_400L;
        final long hours = (seconds % 86_400L) / 3_600L;
        final long minutes = (seconds % 3_600L) / 60L;
        final long rest = seconds % 60L;

        if (days > 0L) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + rest + "s";
        }
        return rest + "s";
    }

    /**
     * Playtime as it appears on the compact scoreboard: {@code 2d 6h 42m}, {@code 6h 42m},
     * {@code 42m}, {@code <1m} or {@code 0m}. Unlike {@link #duration(long)} this never shows
     * seconds, so the line only changes once a minute.
     */
    public static String playtime(final long ticks) {
        final long seconds = Math.max(0L, ticks) / 20L;
        final long days = seconds / 86_400L;
        final long hours = (seconds % 86_400L) / 3_600L;
        final long minutes = (seconds % 3_600L) / 60L;

        if (days > 0L) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m";
        }
        return seconds > 0L ? "<1m" : "0m";
    }

    /** A timestamp in the server's time zone, e.g. {@code 12 Nov 2026 at 22:56}. */
    public static String date(final long millis) {
        return date(millis, ZoneId.systemDefault());
    }

    /** Same as {@link #date(long)}, but with an explicit zone so tests stay stable. */
    public static String date(final long millis, final ZoneId zone) {
        return DATE.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** A rough age, e.g. {@code 3 days ago}, so a join date reads at a glance. */
    public static String relative(final long millis, final Instant now) {
        if (millis <= 0L) {
            return "never";
        }

        final long days = Duration.between(Instant.ofEpochMilli(millis), now).toDays();
        if (days <= 0L) {
            return "today";
        }
        if (days < 60L) {
            return days + " " + plural(days, "day") + " ago";
        }

        final long months = days / 30L;
        if (months < 24L) {
            return months + " " + plural(months, "month") + " ago";
        }
        final long years = days / 365L;
        return years + " " + plural(years, "year") + " ago";
    }

    private static String plural(final long value, final String word) {
        return value == 1L ? word : word + "s";
    }

    private static DecimalFormat decimal(final String pattern) {
        // DecimalFormat is not thread safe, so a fresh instance is made per call; these are cheap.
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
    }
}
