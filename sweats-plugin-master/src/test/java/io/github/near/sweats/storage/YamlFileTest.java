package io.github.near.sweats.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlFileTest {

    @TempDir
    Path folder;

    @Test
    void savesAndLoadsAtomically() {
        final Path path = folder.resolve("data.yml");
        final YamlFile file = new YamlFile(path);
        final List<String> warnings = new ArrayList<>();

        assertFalse(file.exists());
        assertNull(file.load(warnings::add));
        assertTrue(warnings.isEmpty());

        final YamlConfiguration config = new YamlConfiguration();
        config.set("answer", 42);
        file.save(config, warnings::add);

        assertTrue(file.exists());
        assertTrue(warnings.isEmpty());
        assertEquals(42, file.load(warnings::add).getInt("answer"));
        assertFalse(Files.exists(folder.resolve("data.yml.tmp")));
    }

    @Test
    void corruptFilesAreKeptAsACopy() throws IOException {
        final Path path = folder.resolve("ranks.yml");
        final String broken = "ranks: [unclosed\n";
        Files.writeString(path, broken);

        final YamlFile file = new YamlFile(path);
        final List<String> warnings = new ArrayList<>();

        assertNull(file.load(warnings::add));
        assertEquals(1, warnings.size());
        assertEquals(broken, Files.readString(path));

        try (Stream<Path> entries = Files.list(folder)) {
            assertTrue(entries.anyMatch(entry ->
                    entry.getFileName().toString().startsWith("ranks.yml.corrupt-")));
        }
    }

    @Test
    void savesCreateMissingFolders() {
        final Path path = folder.resolve("nested").resolve("data.yml");
        final YamlFile file = new YamlFile(path);
        final YamlConfiguration config = new YamlConfiguration();
        config.set("value", "kept");

        file.save(config, message -> {
            throw new AssertionError(message);
        });

        assertEquals("kept", file.load(message -> {
            throw new AssertionError(message);
        }).getString("value"));
    }

    @Test
    void failedSavesLeaveThePreviousFileIntact() throws IOException {
        final Path path = folder.resolve("data.yml");
        final YamlFile file = new YamlFile(path);
        final List<String> warnings = new ArrayList<>();

        final YamlConfiguration first = new YamlConfiguration();
        first.set("value", "first");
        assertTrue(file.save(first, warnings::add));

        final Path temp = folder.resolve("data.yml.tmp");
        Files.createDirectory(temp);
        Files.writeString(temp.resolve("blocker"), "x");

        final YamlConfiguration second = new YamlConfiguration();
        second.set("value", "second");
        assertFalse(file.save(second, warnings::add));

        assertEquals("first", file.load(message -> {
            throw new AssertionError(message);
        }).getString("value"));
        assertEquals(1, warnings.size());
    }

    @Test
    void failedMovesDoNotLeaveATemporaryFile() throws IOException {
        final Path path = folder.resolve("data.yml");
        Files.createDirectory(path);
        Files.writeString(path.resolve("blocker"), "x");

        final YamlFile file = new YamlFile(path);
        final List<String> warnings = new ArrayList<>();
        final YamlConfiguration config = new YamlConfiguration();
        config.set("value", "second");

        assertFalse(file.save(config, warnings::add));
        assertEquals(1, warnings.size());
        assertFalse(Files.exists(folder.resolve("data.yml.tmp")));
    }
}
