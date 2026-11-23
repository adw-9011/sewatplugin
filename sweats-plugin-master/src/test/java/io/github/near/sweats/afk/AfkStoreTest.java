package io.github.near.sweats.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AfkStoreTest {

    @TempDir
    Path folder;

    @Test
    void persistsAfkSecondsAcrossRestarts() {
        final Path file = folder.resolve("afk.yml");
        final AfkStore store = new AfkStore(file);
        store.load(message -> {});
        final UUID id = UUID.randomUUID();

        assertFalse(store.dirty());
        store.addSeconds(id, "Bhosra", 90L);
        store.addSeconds(id, "Bhosra", 30L);
        assertEquals(120L, store.afkSeconds(id));
        assertTrue(store.dirty());

        store.save(message -> {});
        assertFalse(store.dirty());

        final AfkStore reloaded = new AfkStore(file);
        reloaded.load(message -> {});
        assertEquals(120L, reloaded.afkSeconds(id));
        assertEquals(0L, reloaded.afkSeconds(UUID.randomUUID()));
    }

    @Test
    void ignoresMissingOrNegativeValues() {
        final AfkStore store = new AfkStore(folder.resolve("afk.yml"));
        store.load(message -> {});
        final UUID id = UUID.randomUUID();

        store.addSeconds(id, "Near", 0L);
        store.addSeconds(null, "Near", 50L);

        assertEquals(0L, store.afkSeconds(id));
        assertFalse(store.dirty());
    }
}
