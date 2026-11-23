package io.github.near.sweats.players;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealPlayerRegistryTest {

    @TempDir
    Path folder;

    @Test
    void onlyStoresValidNamesAndFindsThemCaseInsensitively() {
        final RealPlayerRegistry registry = new RealPlayerRegistry(
                folder.resolve("real-players.yml"), message -> {});
        final UUID alex = UUID.randomUUID();

        assertTrue(registry.markJoined(alex, "Alex"));
        assertFalse(registry.markJoined(alex, "Alex"));
        assertTrue(registry.isReal(alex));
        assertEquals("Alex", registry.name(alex));
        assertEquals(alex, registry.findByName("aLeX").orElseThrow());

        assertFalse(registry.markJoined(UUID.randomUUID(), "103.173.20.139"));
        assertFalse(registry.markJoined(UUID.randomUUID(), "way_too_long_a_player_name"));
        assertEquals(1, registry.size());
    }

    @Test
    void countsAPlayerWhoseNameLooksLikeAShopkeeper() {
        final RealPlayerRegistry registry = new RealPlayerRegistry(
                folder.resolve("real-players.yml"), message -> {});
        final UUID manager = UUID.nameUUIDFromBytes("Shopkeeperkisuke".getBytes());

        assertTrue(registry.markJoined(manager, "Shopkeeperkisuke"));
        assertTrue(registry.isReal(manager));
        assertEquals(manager, registry.findByName("shopkeeperkisuke").orElseThrow());
        assertEquals("Shopkeeperkisuke", registry.name(manager));
    }

    @Test
    void persistsAcrossRestartsAndSeedsOfflineModeAccounts() {
        final Path file = folder.resolve("real-players.yml");
        final UUID offline = UUID.nameUUIDFromBytes("Bhosra".getBytes());
        final RealPlayerRegistry first = new RealPlayerRegistry(file, message -> {});
        first.seed(List.of(new RealPlayerRegistry.Identity(offline, "Bhosra")));
        first.markJoined(UUID.randomUUID(), "Near");
        first.save();

        final RealPlayerRegistry reloaded = new RealPlayerRegistry(file, message -> {});
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.isReal(offline));
        assertEquals(offline, reloaded.findByName("bhosra").orElseThrow());
    }

    @Test
    void seedIgnoresMissingNames() {
        final RealPlayerRegistry registry = new RealPlayerRegistry(
                folder.resolve("real-players.yml"), message -> {});
        registry.seed(List.of(new RealPlayerRegistry.Identity(UUID.randomUUID(), null)));
        assertEquals(0, registry.size());
    }
}
