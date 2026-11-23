package io.github.near.sweats.logging.discord;

import java.util.Locale;
import java.util.regex.Pattern;

/** Escaping and secret redaction for anything that leaves the server for Discord. */
public final class SensitiveData {

    private static final Pattern WEBHOOK = Pattern.compile(
            "(?i)https?://(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/api/webhooks/\\S+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|pass|token|secret|api[-_ ]?key|authorization)\\b"
                    + "\\s*[=:]\\s*(\"[^\"]*\"|'[^']*'|\\S+)");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern MENTION = Pattern.compile("(?i)@(everyone|here)");

    private SensitiveData() {}

    /** Removes webhook URLs and credential values while keeping the key visible for context. */
    public static String redact(final String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String result = WEBHOOK.matcher(input).replaceAll("<webhook>");
        result = BEARER.matcher(result).replaceAll("Bearer ***");
        result = SECRET.matcher(result).replaceAll("$1=***");
        return result;
    }

    /** Neutralises @everyone and @here even if allowed_mentions ever changes. */
    public static String escapeMentions(final String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return MENTION.matcher(input).replaceAll("@\u200B$1");
    }

    /** Strips backticks so a value cannot break out of an inline code span. */
    public static String inline(final String input) {
        if (input == null) {
            return "";
        }
        return input.replace('`', '\'').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Full pipeline for webhook content: redact secrets, neutralise mentions, remove control
     * characters (newlines are kept for readable multi-line records) and cap the length.
     */
    public static String clean(final String input, final int maxLength) {
        if (input == null) {
            return "";
        }
        final String redacted = escapeMentions(redact(input));
        final StringBuilder out = new StringBuilder(Math.min(redacted.length(), maxLength + 16));
        for (int index = 0; index < redacted.length(); index++) {
            final char character = redacted.charAt(index);
            if (character == '\n' || character == '\t' || !Character.isISOControl(character)) {
                out.append(character);
            }
            if (out.length() >= maxLength) {
                break;
            }
        }
        if (out.length() >= maxLength) {
            return out.substring(0, Math.max(0, maxLength - 3)) + "...";
        }
        return out.toString();
    }

    /** Lower-cases a command label and strips a leading slash. */
    public static String commandLabel(final String raw) {
        if (raw == null) {
            return "";
        }
        final String trimmed = raw.trim();
        final String withoutSlash = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        final int space = withoutSlash.indexOf(' ');
        final String label = space < 0 ? withoutSlash : withoutSlash.substring(0, space);
        return label.toLowerCase(Locale.ROOT);
    }
}
