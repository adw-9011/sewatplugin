package io.github.near.sweats.leaderboard;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Renders the text that floats above a leaderboard.
 *
 * <p>The first line is personal - the viewer's own score - and the rest is the shared top ten.
 * Every category has its own heading, colour and value format, so a kills board looks different
 * from a votes board. Keeping this a pure function makes it testable without a server.
 */
public final class LeaderboardLayout {

    public static final int TOP_SIZE = 10;

    private LeaderboardLayout() {}

    /** Legacy playtime rendering, kept for callers that still pass {@link PlaytimeEntry}. */
    public static Component display(
            final long viewerTicks, final List<PlaytimeEntry> ranking) {
        return display(LeaderboardCategory.PLAYTIME, viewerTicks, convert(ranking), false);
    }

    /** The full multi-line component handed to a {@code TextDisplay}. */
    public static Component display(
            final LeaderboardCategory category,
            final long viewerValue,
            final List<LeaderboardEntry> ranking) {
        return display(category, viewerValue, ranking, true);
    }

    private static Component display(
            final LeaderboardCategory category,
            final long viewerValue,
            final List<LeaderboardEntry> ranking,
            final boolean themed) {
        final List<Component> lines = lines(category, viewerValue, ranking, themed);
        Component result = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(lines.get(index));
        }
        return result;
    }

    /** Legacy playtime lines. */
    public static List<Component> lines(
            final long viewerTicks, final List<PlaytimeEntry> ranking) {
        return lines(LeaderboardCategory.PLAYTIME, viewerTicks, convert(ranking), false);
    }

    /** The themed lines, in display order. */
    public static List<Component> lines(
            final LeaderboardCategory category,
            final long viewerValue,
            final List<LeaderboardEntry> ranking) {
        return lines(category, viewerValue, ranking, true);
    }

    private static List<Component> lines(
            final LeaderboardCategory category,
            final long viewerValue,
            final List<LeaderboardEntry> ranking,
            final boolean themed) {
        final List<Component> lines = new ArrayList<>(TOP_SIZE + 3);
        if (themed) {
            lines.add(Component.text(
                    category.symbol() + " " + category.title() + " " + category.symbol(),
                    category.color()));
        }
        final TextColor accent = themed ? category.color() : NamedTextColor.AQUA;
        lines.add(Component.text("Your " + category.viewerLabel() + ": ", NamedTextColor.GRAY)
                .append(Component.text(category.format(viewerValue), accent)));

        final int shown = Math.min(TOP_SIZE, ranking.size());
        if (shown > 0) {
            lines.add(Component.empty());
        }
        for (int index = 0; index < shown; index++) {
            final LeaderboardEntry entry = ranking.get(index);
            lines.add(Component.text("#" + (index + 1), NamedTextColor.GOLD)
                    .append(Component.text(" " + entry.name(), NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(category.format(entry.value()), accent)));
        }
        return List.copyOf(lines);
    }

    private static List<LeaderboardEntry> convert(final List<PlaytimeEntry> ranking) {
        final List<LeaderboardEntry> converted = new ArrayList<>(ranking.size());
        for (final PlaytimeEntry entry : ranking) {
            converted.add(new LeaderboardEntry(entry.id(), entry.name(), entry.ticks()));
        }
        return converted;
    }
}
