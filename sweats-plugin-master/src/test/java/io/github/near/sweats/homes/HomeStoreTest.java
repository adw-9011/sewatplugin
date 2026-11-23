package io.github.near.sweats.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeStoreTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();

    private Path file() {
        return folder.resolve("homes.yml");
    }

    @Test
    void savesAndLoadsEverySlotIncludingLockedOnes() {
        final UUID id = UUID.randomUUID();
        final HomeStore store = new HomeStore(file());
        final Home first = new Home(1, "world", 10.5, 64, -3.25, 90, 12);
        final Home fifth = new Home(5, "world_nether", 1, 70, 2, 180, -5);

        assertTrue(store.put(id, first));
        assertTrue(store.put(id, fifth));
        assertTrue(store.save(warnings::add));
        assertTrue(warnings.isEmpty());

        final HomeStore loaded = new HomeStore(file());
        loaded.load(warnings::add);
        assertTrue(warnings.isEmpty());
        assertEquals(first, loaded.get(id, 1).orElseThrow());
        assertEquals(fifth, loaded.get(id, 5).orElseThrow());
        assertEquals(2, loaded.homes(id).size());
    }

    @Test
    void replacingAHomeReportsWhetherItChanged() {
        final UUID id = UUID.randomUUID();
        final HomeStore store = new HomeStore(file());
        final Home home = new Home(1, "world", 0, 64, 0, 0, 0);

        assertTrue(store.put(id, home));
        assertFalse(store.put(id, home));
        assertTrue(store.put(id, home.withSlot(2)));
        assertEquals(home, store.get(id, 1).orElseThrow());
    }

    @Test
    void removingTheLastHomeDropsTheEmptyPlayerEntry() {
        final UUID id = UUID.randomUUID();
        final HomeStore store = new HomeStore(file());
        store.put(id, new Home(1, "world", 0, 64, 0, 0, 0));

        assertEquals(1, store.remove(id, 1).orElseThrow().slot());
        assertTrue(store.homes(id).isEmpty());
        assertTrue(store.remove(id, 1).isEmpty());
    }

    @Test
    void ignoresInvalidPlayersSlotsAndMissingWorlds() throws Exception {
        final UUID valid = UUID.randomUUID();
        final YamlConfiguration config = new YamlConfiguration();
        config.set("homes.not-a-uuid.1.world", "world");
        config.set("homes.not-a-uuid.1.x", 1);
        config.set("homes." + valid + ".nope.world", "world");
        config.set("homes." + valid + ".0.world", "world");
        config.set("homes." + valid + "-1.world", "world");
        config.set("homes." + valid + ".3.x", 1);
        config.set("homes." + valid + ".4.world", " ");
        config.save(file().toFile());

        final HomeStore store = new HomeStore(file());
        store.load(warnings::add);

        assertTrue(store.homes(valid).isEmpty());
        assertEquals(6, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("invalid UUID")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("invalid slot")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("slot must be positive")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("no world")));
    }

    @Test
    void aBrokenFileIsCopiedAsideInsteadOfCrashing() throws Exception {
        java.nio.file.Files.writeString(file(), "homes: [broken");

        final HomeStore store = new HomeStore(file());
        store.load(warnings::add);

        assertTrue(store.homes(UUID.randomUUID()).isEmpty());
        assertFalse(warnings.isEmpty());
        try (var files = java.nio.file.Files.list(folder)) {
            final List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(name -> name.startsWith("homes.yml.corrupt-")),
                    names.toString());
        }
    }

    @Test
    void savingAnEmptyStoreWritesAReadableFile() {
        final HomeStore store = new HomeStore(file());
        assertTrue(store.save(warnings::add));

        final YamlConfiguration config =
                YamlConfiguration.loadConfiguration(file().toFile());
        assertNotNull(config);
        assertTrue(config.getKeys(false).isEmpty());
    }
}
