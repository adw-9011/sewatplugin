package io.github.near.sweats.leaderboard;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Sorts score rows best-first with a stable tie break, then caps the list at the top ten. */
public final class LeaderboardRanking {

    private static final Comparator<LeaderboardEntry> BEST_FIRST =
            Comparator.comparingLong(LeaderboardEntry::value)
                    .reversed()
                    .thenComparing(LeaderboardEntry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> entry.id().toString());

    private LeaderboardRanking() {}

    public static List<LeaderboardEntry> rank(
            final Collection<LeaderboardEntry> entries, final int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return entries.stream()
                .filter(entry -> entry.value() > 0L)
                .filter(entry -> entry.name() != null && !entry.name().isBlank())
                .sorted(BEST_FIRST)
                .limit(limit)
                .toList();
    }
}
