package io.github.near.sweats.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.maintenance.MaintenanceStore.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceStoreTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void changesAreSavedAndReported() {
        final MaintenanceStore store =
                new MaintenanceStore(folder.resolve("maintenance.yml"), warnings::add);

        assertEquals(Result.CHANGED, store.setEnabled(true));
        assertTrue(store.state().enabled());
        assertEquals(Result.UNCHANGED, store.setEnabled(true));

        assertEquals(Result.CHANGED, store.addBypass("Alex"));
        assertTrue(store.state().hasBypass("alex"));
        assertEquals(Result.CHANGED, store.removeBypass("ALEX"));
        assertFalse(store.state().hasBypass("Alex"));
    }

    @Test
    void aFailedSaveRollsBackTheSwitchAndBypassList() throws IOException {
        final Path path = blockedPath("maintenance.yml");
        final MaintenanceStore store = new MaintenanceStore(path, warnings::add);

        assertEquals(Result.SAVE_FAILED, store.setEnabled(true));
        assertFalse(store.state().enabled());

        assertEquals(Result.SAVE_FAILED, store.addBypass("Alex"));
        assertTrue(store.state().bypass().isEmpty());
    }

    /** A non-empty directory in place of the YAML file makes the atomic move fail. */
    private Path blockedPath(final String name) throws IOException {
        final Path path = folder.resolve(name);
        Files.createDirectories(path);
        Files.writeString(path.resolve("block"), "not a yaml file");
        return path;
    }
}
