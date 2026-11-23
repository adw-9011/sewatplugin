package io.github.near.sweats.moderation;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    /** One month is defined as exactly 30 days. */
    public static final long MONTH_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    private static final Pattern DURATION = Pattern.compile("(\\d+)(mo|[smhdw])");

    private DurationParser() {}

    public static PunishmentDuration parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(raw);
        }

        final String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("permanent")) {
            return PunishmentDuration.ofPermanent();
        }

        final Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            throw invalid(raw);
        }

        final long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException("Duration is too large: " + raw);
        }

        final long unit = switch (matcher.group(2)) {
            case "s" -> 1000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 7L * 86_400_000L;
            case "mo" -> MONTH_MILLIS;
            default -> throw invalid(raw);
        };

        final long millis;
        try {
            millis = Math.multiplyExact(amount, unit);
        } catch (final ArithmeticException exception) {
            throw new IllegalArgumentException("Duration is too large: " + raw);
        }
        if (millis <= 0L) {
            throw new IllegalArgumentException("Duration must be greater than zero.");
        }
        return new PunishmentDuration(millis, false);
    }

    private static IllegalArgumentException invalid(final String raw) {
        return new IllegalArgumentException("Invalid duration: " + raw
                + ". Use 30s, 10m, 2h, 7d, 2w, 1mo or permanent.");
    }
}
