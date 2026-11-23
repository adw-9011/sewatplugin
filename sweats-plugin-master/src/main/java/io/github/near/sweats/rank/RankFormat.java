package io.github.near.sweats.rank;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Parses Minecraft legacy formatting codes ({@code &0-9}, {@code &a-f}, {@code &k-r})
 * into Adventure {@link Component} formatting.
 *
 * <p>Supported codes:
 * <ul>
 *   <li>Colors: {@code &0}–{@code &9}, {@code &a}–{@code &f}</li>
 *   <li>Formatting: {@code &k} obfuscated, {@code &l} bold, {@code &m} strikethrough,
 *       {@code &n} underline, {@code &o} italic</li>
 *   <li>Reset: {@code &r}</li>
 * </ul>
 *
 * <p>Only valid codes are parsed; invalid sequences remain as literal text.
 * The parser is strict to prevent injection of arbitrary formatting.
 */
final class RankFormat {

    private static final Pattern LEGACY_CODE = Pattern.compile("&([0-9a-fklmnorKLMNOR])", Pattern.CASE_INSENSITIVE);

    private RankFormat() {}

    /**
     * Parses a rank name containing legacy formatting codes into a formatted {@link Component}.
     *
     * @param raw the raw rank name (may contain {@code &} codes)
     * @return a Component with the parsed formatting applied
     */
    public static Component parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Component.text("Unknown");
        }

        // Split the input by legacy codes, keeping the delimiters
        final Matcher matcher = LEGACY_CODE.matcher(raw);
        final StringBuilder plainText = new StringBuilder();
        final java.util.List<Segment> segments = new java.util.ArrayList<>();

        int lastEnd = 0;
        TextColor currentColor = NamedTextColor.WHITE;
        boolean bold = false, italic = false, underlined = false, strikethrough = false, obfuscated = false;

        while (matcher.find()) {
            // Text before the code
            if (matcher.start() > lastEnd) {
                String text = raw.substring(lastEnd, matcher.start());
                if (!text.isEmpty()) {
                    segments.add(new Segment(text, currentColor, bold, italic, underlined, strikethrough, obfuscated));
                }
            }

            // Parse the formatting code
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            switch (code) {
                case '0' -> currentColor = NamedTextColor.BLACK;
                case '1' -> currentColor = NamedTextColor.DARK_BLUE;
                case '2' -> currentColor = NamedTextColor.DARK_GREEN;
                case '3' -> currentColor = NamedTextColor.DARK_AQUA;
                case '4' -> currentColor = NamedTextColor.DARK_RED;
                case '5' -> currentColor = NamedTextColor.DARK_PURPLE;
                case '6' -> currentColor = NamedTextColor.GOLD;
                case '7' -> currentColor = NamedTextColor.GRAY;
                case '8' -> currentColor = NamedTextColor.DARK_GRAY;
                case '9' -> currentColor = NamedTextColor.BLUE;
                case 'a' -> currentColor = NamedTextColor.GREEN;
                case 'b' -> currentColor = NamedTextColor.AQUA;
                case 'c' -> currentColor = NamedTextColor.RED;
                case 'd' -> currentColor = NamedTextColor.LIGHT_PURPLE;
                case 'e' -> currentColor = NamedTextColor.YELLOW;
                case 'f' -> currentColor = NamedTextColor.WHITE;
                case 'k' -> obfuscated = true;
                case 'l' -> bold = true;
                case 'm' -> strikethrough = true;
                case 'n' -> underlined = true;
                case 'o' -> italic = true;
                case 'r' -> {
                    currentColor = NamedTextColor.WHITE;
                    bold = italic = underlined = strikethrough = obfuscated = false;
                }
                default -> {
                    // Invalid code - treat as literal text
                    String literal = "&" + matcher.group(1);
                    segments.add(new Segment(literal, currentColor, bold, italic, underlined, strikethrough, obfuscated));
                }
            }
            lastEnd = matcher.end();
        }

        // Remaining text after last code
        if (lastEnd < raw.length()) {
            String text = raw.substring(lastEnd);
            if (!text.isEmpty()) {
                segments.add(new Segment(text, currentColor, bold, italic, underlined, strikethrough, obfuscated));
            }
        }

        // Build the component
        Component result = Component.empty();
        for (Segment seg : segments) {
            result = result.append(seg.toComponent());
        }
        return result;
    }

    /**
     * Extracts the plain text from a rank name (removes formatting codes).
     * Used for generating the internal key.
     */
    public static String stripCodes(String raw) {
        if (raw == null) {
            return "";
        }
        return LEGACY_CODE.matcher(raw).replaceAll("");
    }

    private record Segment(
            String text,
            TextColor color,
            boolean bold,
            boolean italic,
            boolean underlined,
            boolean strikethrough,
            boolean obfuscated) {

        Component toComponent() {
            Component result = Component.text(text, color);
            if (bold) result = result.decoration(TextDecoration.BOLD, true);
            if (italic) result = result.decoration(TextDecoration.ITALIC, true);
            if (underlined) result = result.decoration(TextDecoration.UNDERLINED, true);
            if (strikethrough) result = result.decoration(TextDecoration.STRIKETHROUGH, true);
            if (obfuscated) result = result.decoration(TextDecoration.OBFUSCATED, true);
            return result;
        }
    }
}