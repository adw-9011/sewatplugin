package io.github.near.sweats.leaderboard;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a bag of playtime rows into a ranked top list.
 *
 * <p>Rows with no playtime (for example a player the server has data for but no statistics yet)
 * are dropped, and ties are broken by name so the order does not flicker between refreshes.
 */
public final class PlaytimeLeaderboard {

    private static final Comparator<PlaytimeEntry> BEST_FIRST =
            Comparator.comparingLong(PlaytimeEntry::ticks)
                    .reversed()
                    .thenComparing(PlaytimeEntry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> entry.id().toString());

    private PlaytimeLeaderboard() {}

    public static List<PlaytimeEntry> rank(
            final Collection<PlaytimeEntry> entries, final int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return entries.stream()
                .filter(entry -> entry.ticks() > 0L)
                .filter(entry -> entry.name() != null && !entry.name().isBlank())
                .sorted(BEST_FIRST)
                .limit(limit)
                .toList();
    }
}
