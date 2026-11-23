package io.github.near.sweats.enderchest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnderChestStoreTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();

    private Path file() {
        return folder.resolve("enderchests.yml");
    }

    @Test
    void storesOnlyTheExtraSlotsAndReturnsDefensiveCopies() {
        final UUID id = UUID.randomUUID();
        final EnderChestStore store = new EnderChestStore(file());
        final ItemStack[] contents = new ItemStack[EnderChestStore.MAX_EXTRA_SLOTS];
        contents[3] = new FakeItemStack("diamond");

        store.put(id, contents);

        final ItemStack[] first = store.extra(id);
        assertEquals("diamond", marker(first[3]));
        assertNull(first[0]);

        first[3] = null;
        assertEquals("diamond", marker(store.extra(id)[3]));
        assertTrue(store.save(warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void emptyChestsAreDroppedInsteadOfSaved() {
        final UUID id = UUID.randomUUID();
        final EnderChestStore store = new EnderChestStore(file());
        store.put(id, new ItemStack[EnderChestStore.MAX_EXTRA_SLOTS]);

        assertNull(store.extra(id)[0]);

        final ItemStack[] contents = new ItemStack[EnderChestStore.MAX_EXTRA_SLOTS];
        contents[0] = new FakeItemStack("stone");
        store.put(id, contents);
        assertEquals("stone", marker(store.extra(id)[0]));

        store.put(id, new ItemStack[EnderChestStore.MAX_EXTRA_SLOTS]);
        assertNull(store.extra(id)[0]);
    }

    @Test
    void savesBase64ItemBytesUnderThePlayerUuidAndSlot() {
        final UUID id = UUID.randomUUID();
        final EnderChestStore store = new EnderChestStore(file());
        final ItemStack[] contents = new ItemStack[EnderChestStore.MAX_EXTRA_SLOTS];
        contents[3] = new FakeItemStack("diamond");
        store.put(id, contents);

        assertTrue(store.save(warnings::add));

        final YamlConfiguration config = YamlConfiguration.loadConfiguration(file().toFile());
        final String encoded = config.getString("chests." + id + ".3");
        assertNotNull(encoded);
        assertEquals("diamond",
                new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        assertNull(config.getString("chests." + id + ".0"));
    }

    @Test
    void loadIgnoresInvalidUuidsSlotsAndUnreadableItems() throws Exception {
        final UUID valid = UUID.randomUUID();
        final YamlConfiguration config = new YamlConfiguration();
        config.set("chests.not-a-uuid.0", "AAAA");
        config.set("chests." + valid + ".not-a-slot", "AAAA");
        config.set("chests." + valid + ".27", "AAAA");
        config.set("chests." + valid + ".1", "!!!not-base64!!!");
        config.save(file().toFile());

        final EnderChestStore store = new EnderChestStore(file());
        store.load(warnings::add);

        assertNull(store.extra(valid)[0]);
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("invalid UUID")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Ignored ender chest slot")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("valid slots are 0-26")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("unreadable ender chest item")));
    }

    @Test
    void loadingAMissingFileLeavesTheStoreEmpty() {
        final UUID id = UUID.randomUUID();
        final EnderChestStore store = new EnderChestStore(file());

        store.load(warnings::add);

        assertNull(store.extra(id)[0]);
        assertTrue(warnings.isEmpty());
    }

    private static String marker(final ItemStack item) {
        return item instanceof final FakeItemStack fake ? fake.marker() : null;
    }

    /**
     * A bare ItemStack stand-in. Serverless JUnit cannot create a real ItemStack because Paper's
     * material registry needs a running server, so only the methods the store uses are overridden.
     */
    private static final class FakeItemStack extends ItemStack {

        private final String marker;

        private FakeItemStack(final String marker) {
            this.marker = marker;
        }

        private String marker() {
            return marker;
        }

        @Override
        public boolean isEmpty() {
            return marker == null || marker.isBlank();
        }

        @Override
        public FakeItemStack clone() {
            return new FakeItemStack(marker);
        }

        @Override
        public byte[] serializeAsBytes() {
            return marker.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return "FakeItemStack[" + marker + "]";
        }
    }
}
