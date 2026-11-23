package io.github.near.sweats.chat;

import java.util.Collection;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextColor;

/**
 * Recolors player names inside a chat message.
 *
 * <p>Only the name's color changes: every other character keeps the style the chat plugin (ranks,
 * formats, etc.) applied to it, so this composes with formatting plugins instead of replacing them.
 * Pure Adventure code, no server state, so it can be unit tested directly.
 */
public final class MentionRenderer {

    private static final String NAME_BOUNDARY_BEFORE = "(?<![A-Za-z0-9_])";
    private static final String NAME_BOUNDARY_AFTER = "(?![A-Za-z0-9_])";

    private MentionRenderer() {}

    /**
     * Builds the mention pattern for a set of names.
     *
     * <p>Names are alternated longest first so that {@code Bobby} wins over {@code Bob}, quoted so
     * no name is read as regex, and wrapped in name boundaries so {@code Player} never matches
     * inside {@code Player123} or {@code xPlayer}.
     */
    public static Pattern pattern(final Collection<String> names) {
        final String alternation = names.stream()
                .filter(name -> !name.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));

        if (alternation.isEmpty()) {
            return null;
        }

        // "@" is optional so "@Player1" and "Player1" both light up.
        return Pattern.compile(
                NAME_BOUNDARY_BEFORE + "@?(?:" + alternation + ")" + NAME_BOUNDARY_AFTER,
                Pattern.CASE_INSENSITIVE);
    }

    /**
     * Returns a copy of {@code message} with every mention recolored.
     *
     * @param message the chat message to decorate
     * @param mentionPattern a pattern from {@link #pattern(Collection)}, or {@code null}
     * @param color the color to apply to mentions
     */
    public static Component highlight(
            final Component message, final Pattern mentionPattern, final TextColor color) {
        if (mentionPattern == null || message == null) {
            return message;
        }

        return message.replaceText(TextReplacementConfig.builder()
                .match(mentionPattern)
                .replacement((match, builder) -> builder.color(color))
                .build());
    }

    /** Convenience overload for callers that do not cache the pattern. */
    public static Component highlight(
            final Component message, final Collection<String> names, final TextColor color) {
        return highlight(message, pattern(names), color);
    }
}
