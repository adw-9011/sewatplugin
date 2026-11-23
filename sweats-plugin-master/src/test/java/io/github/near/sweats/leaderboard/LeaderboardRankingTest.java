package io.github.near.sweats.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeaderboardRankingTest {

    private static LeaderboardEntry entry(final String name, final long value) {
        return new LeaderboardEntry(UUID.nameUUIDFromBytes(name.getBytes()), name, value);
    }

    @Test
    void sortsBestFirstWithAStableNameTieBreak() {
        final List<LeaderboardEntry> ranking = LeaderboardRanking.rank(List.of(
                entry("Sam", 20L), entry("Alex", 20L), entry("Lee", 5L)), 10);

        assertEquals(List.of("Alex", "Sam", "Lee"),
                ranking.stream().map(LeaderboardEntry::name).toList());
    }

    @Test
    void dropsEmptyOrInvalidRowsAndRespectsTheLimit() {
        final List<LeaderboardEntry> entries = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            entries.add(entry("P" + index, 100L - index));
        }
        entries.add(entry("Zero", 0L));
        entries.add(entry("", 999L));
        entries.add(new LeaderboardEntry(UUID.randomUUID(), null, 999L));

        final List<LeaderboardEntry> ranking = LeaderboardRanking.rank(entries, 10);

        assertEquals(10, ranking.size());
        assertEquals("P0", ranking.getFirst().name());
        assertEquals("P9", ranking.getLast().name());
    }

    @Test
    void aNonPositiveLimitReturnsNothing() {
        assertEquals(List.of(), LeaderboardRanking.rank(List.of(entry("Alex", 1L)), 0));
    }
}
