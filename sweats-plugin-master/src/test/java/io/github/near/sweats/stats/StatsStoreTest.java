package io.github.near.sweats.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsStoreTest {

    private static final Consumer<String> IGNORE = message -> {};

    @TempDir
    Path folder;

    @Test
    void countsPlacementsAndSurvivesAReload() {
        final Path file = folder.resolve("stats.yml");
        final StatsStore store = new StatsStore(file);
        store.load(IGNORE);

        final UUID id = UUID.randomUUID();
        store.addBlockPlaced(id, "Bhosra");
        store.addBlockPlaced(id, "Bhosra");

        assertEquals(2L, store.blocksPlaced(id));
        assertTrue(store.dirty());

        store.save(IGNORE);
        assertFalse(store.dirty());
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));

        final StatsStore reloaded = new StatsStore(file);
        reloaded.load(IGNORE);
        assertEquals(2L, reloaded.blocksPlaced(id));
        assertEquals(List.of("Bhosra"), reloaded.knownNames());
        assertEquals(0L, reloaded.blocksPlaced(UUID.randomUUID()));
    }

    @Test
    void aMissingFileMeansNothingWasPlacedYet() {
        final StatsStore store = new StatsStore(folder.resolve("missing.yml"));
        store.load(IGNORE);

        assertFalse(store.dirty());
        assertEquals(0L, store.blocksPlaced(UUID.randomUUID()));
        assertTrue(store.knownNames().isEmpty());
    }

    @Test
    void savingWithoutChangesDoesNotTouchTheDisk() {
        final StatsStore store = new StatsStore(folder.resolve("stats.yml"));
        store.load(IGNORE);
        store.save(IGNORE);

        assertFalse(Files.exists(folder.resolve("stats.yml")));
    }

    @Test
    void aBrokenFileIsReportedAndPreservedInsteadOfCrashing() throws Exception {
        final Path file = folder.resolve("stats.yml");
        final String broken = "players: [this is not valid\n";
        Files.writeString(file, broken);

        final StatsStore store = new StatsStore(file);
        final List<String> warnings = new ArrayList<>();
        store.load(warnings::add);

        assertEquals(0L, store.blocksPlaced(UUID.randomUUID()));
        assertFalse(store.dirty());
        assertFalse(warnings.isEmpty());
        try (var entries = Files.list(folder)) {
            final Path backup = entries
                    .filter(path -> path.getFileName().toString().startsWith("stats.yml.corrupt-"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(broken, Files.readString(backup));
        }
    }

    @Test
    void ignoresEntriesThatAreNotUuids() throws Exception {
        final Path file = folder.resolve("stats.yml");
        Files.writeString(file, """
                players:
                  not-a-uuid:
                    name: Ghost
                    blocks-placed: 5
                """);

        final StatsStore store = new StatsStore(file);
        store.load(IGNORE);

        assertTrue(store.knownNames().isEmpty());
        assertEquals(0L, store.blocksPlaced(UUID.randomUUID()));
    }
}
