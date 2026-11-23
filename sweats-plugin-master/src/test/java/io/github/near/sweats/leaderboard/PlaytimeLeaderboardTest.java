package io.github.near.sweats.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaytimeLeaderboardTest {

    private static PlaytimeEntry entry(final String name, final long ticks) {
        return new PlaytimeEntry(UUID.nameUUIDFromBytes(name.getBytes()), name, ticks);
    }

    @Test
    void ranksTheLongestPlaytimeFirst() {
        final List<PlaytimeEntry> ranked = PlaytimeLeaderboard.rank(
                List.of(entry("Sam", 20L), entry("Alex", 90L), entry("Kim", 50L)), 10);

        assertEquals(List.of("Alex", "Kim", "Sam"), ranked.stream().map(PlaytimeEntry::name).toList());
    }

    @Test
    void dropsPlayersWithoutPlaytimeOrAName() {
        final List<PlaytimeEntry> ranked = PlaytimeLeaderboard.rank(
                List.of(entry("Zero", 0L), entry("", 50L), entry("Alex", 10L)), 10);

        assertEquals(1, ranked.size());
        assertEquals("Alex", ranked.getFirst().name());
    }

    @Test
    void tiesAreBrokenByNameSoTheOrderIsStable() {
        final List<PlaytimeEntry> ranked = PlaytimeLeaderboard.rank(
                List.of(entry("Sam", 10L), entry("Alex", 10L)), 10);

        assertEquals(List.of("Alex", "Sam"), ranked.stream().map(PlaytimeEntry::name).toList());
    }

    @Test
    void appliesTheLimitAndRejectsNonPositiveLimits() {
        assertEquals(2, PlaytimeLeaderboard.rank(
                List.of(entry("A", 3L), entry("B", 2L), entry("C", 1L)), 2).size());
        assertTrue(PlaytimeLeaderboard.rank(List.of(entry("A", 3L)), 0).isEmpty());
        assertTrue(PlaytimeLeaderboard.rank(List.of(entry("A", 3L)), -1).isEmpty());
    }
}
