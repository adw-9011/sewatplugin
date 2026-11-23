package io.github.near.sweats.vote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoteStoreTest {

    @TempDir
    Path folder;

    private static long millis(final LocalDate day) {
        return day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    void computesTheLongestRunOfConsecutiveDays() {
        final LocalDate today = LocalDate.now(ZoneId.systemDefault());
        assertEquals(3, VoteStore.longestStreak(Set.of(
                today, today.minusDays(1), today.minusDays(2))));
        assertEquals(2, VoteStore.longestStreak(Set.of(
                today, today.minusDays(1), today.minusDays(5), today.minusDays(6))));
        assertEquals(1, VoteStore.longestStreak(Set.of(today)));
        assertEquals(0, VoteStore.longestStreak(Set.of()));
    }

    @Test
    void countsTotalsAndKeepsTheBestStreak() {
        final Path file = folder.resolve("votes.yml");
        final VoteStore store = new VoteStore(file);
        store.load(message -> {});
        final UUID id = UUID.randomUUID();
        final LocalDate today = LocalDate.now(ZoneId.systemDefault());

        store.record(id, "Bhosra", millis(today.minusDays(4)));
        store.record(id, "Bhosra", millis(today.minusDays(3)));
        store.record(id, "Bhosra", millis(today.minusDays(2)));
        // A gap resets the current run, but the best streak of three is kept.
        store.record(id, "Bhosra", millis(today));

        assertEquals(4L, store.total(id));
        assertEquals(3, store.bestStreak(id));
        assertTrue(store.dirty());
        assertEquals(1, store.trackedPlayers());

        store.save(message -> {});
        final VoteStore reloaded = new VoteStore(file);
        reloaded.load(message -> {});
        assertEquals(4L, reloaded.total(id));
        assertEquals(3, reloaded.bestStreak(id));
        assertEquals("Bhosra", reloaded.all().get(id).name());
    }

    @Test
    void ignoresTimestampsThatAreClearlyWrong() {
        final VoteStore store = new VoteStore(folder.resolve("votes.yml"));
        store.load(message -> {});
        final UUID id = UUID.randomUUID();

        store.record(id, "Near", Instant.ofEpochMilli(0).toEpochMilli());
        store.record(id, "Near", System.currentTimeMillis() + 10L * 86_400_000L);

        assertEquals(2L, store.total(id));
        assertEquals(1, store.bestStreak(id));
    }
}
