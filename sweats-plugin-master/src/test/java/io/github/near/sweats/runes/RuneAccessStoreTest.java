package io.github.near.sweats.runes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuneAccessStoreTest {

    @TempDir
    Path folder;

    @Test
    void persistsAccessAndBonusAcrossRestarts() {
        final Path dataFolder = folder.resolve("Sweats");
        final RuneAccessStore store = new RuneAccessStore(dataFolder, message -> {});
        final UUID id = UUID.randomUUID();

        assertFalse(store.has(id));
        assertEquals(0.0, store.bonus(id));

        store.setAccess(id, true);
        store.setBonus(id, 0.25);
        store.close();

        final RuneAccessStore reloaded = new RuneAccessStore(dataFolder, message -> {});
        assertTrue(reloaded.has(id));
        assertEquals(0.25, reloaded.bonus(id), 1e-9);
        assertEquals(1, reloaded.granted().size());

        reloaded.setAccess(id, false);
        reloaded.setBonus(id, 0.0);
        reloaded.close();

        final RuneAccessStore empty = new RuneAccessStore(dataFolder, message -> {});
        assertFalse(empty.has(id));
        assertEquals(0.0, empty.bonus(id));
        assertTrue(empty.granted().isEmpty());
    }

    @Test
    void clampsNegativeBonusesToZero() {
        final RuneAccessStore store =
                new RuneAccessStore(folder.resolve("Sweats"), message -> {});
        final UUID id = UUID.randomUUID();

        store.setBonus(id, -3.5);

        assertEquals(0.0, store.bonus(id));
        assertFalse(store.has(id));
    }

    @Test
    void importsTheLegacySweatyRunesFileOnce() throws IOException {
        final Path plugins = folder.resolve("plugins");
        final UUID id = UUID.randomUUID();
        final Path legacy = plugins.resolve("SweatyRunes").resolve("access.yml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(
                legacy,
                "players:\n  " + id + ":\n    access: true\n    bonus: 0.4\n");

        final RuneAccessStore store =
                new RuneAccessStore(plugins.resolve("Sweats"), message -> {});

        assertTrue(store.has(id));
        assertEquals(0.4, store.bonus(id), 1e-9);
        assertTrue(Files.isRegularFile(
                plugins.resolve("Sweats").resolve("runes").resolve("access.yml")));
    }

    @Test
    void skipsInvalidIdsAndUnknownLegacyEntries() throws IOException {
        final Path dataFolder = folder.resolve("Sweats");
        final Path file = dataFolder.resolve("runes").resolve("access.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "players:\n"
                        + "  not-a-uuid:\n"
                        + "    access: true\n"
                        + "  " + UUID.randomUUID() + ":\n"
                        + "    access: false\n"
                        + "    bonus: 0\n");

        final RuneAccessStore store = new RuneAccessStore(dataFolder, message -> {});

        assertTrue(store.granted().isEmpty());
    }
}
