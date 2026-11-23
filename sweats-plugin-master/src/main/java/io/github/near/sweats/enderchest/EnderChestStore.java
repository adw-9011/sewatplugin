package io.github.near.sweats.enderchest;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * Persists the extra slots of an ender chest beyond the vanilla 27.
 *
 * <p>The first 27 slots stay in the vanilla ender chest, so existing contents are never copied
 * into a second source of truth. Slots 28-54 are stored here, keyed by player UUID. Losing
 * access to higher slots simply hides them; their items are kept untouched and restored when
 * the rank is upgraded again.
 */
public final class EnderChestStore {

    public static final int MAX_EXTRA_SLOTS = 27; // 54 - 27
    public static final int VANILLA_SLOTS = 27;
    public static final int MAX_TOTAL_SLOTS = VANILLA_SLOTS + MAX_EXTRA_SLOTS;

    private final YamlFile file;
    private final Map<UUID, ItemStack[]> extras = new LinkedHashMap<>();

    public EnderChestStore(final Path file) {
        this.file = new YamlFile(file);
    }

    public void load(final Consumer<String> warn) {
        extras.clear();
        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            return;
        }
        final ConfigurationSection root = config.getConfigurationSection("chests");
        if (root == null) {
            return;
        }

        for (final String playerKey : root.getKeys(false)) {
            final UUID id;
            try {
                id = UUID.fromString(playerKey);
            } catch (final IllegalArgumentException exception) {
                warn.accept("Ignored ender chest for invalid UUID '" + playerKey + "'.");
                continue;
            }
            final ConfigurationSection section = root.getConfigurationSection(playerKey);
            if (section == null) {
                continue;
            }

            final ItemStack[] items = new ItemStack[MAX_EXTRA_SLOTS];
            boolean found = false;
            for (final String slotKey : section.getKeys(false)) {
                final int slot;
                try {
                    slot = Integer.parseInt(slotKey);
                } catch (final NumberFormatException exception) {
                    warn.accept("Ignored ender chest slot '" + slotKey + "' for " + playerKey + ".");
                    continue;
                }
                if (slot < 0 || slot >= MAX_EXTRA_SLOTS) {
                    warn.accept("Ignored ender chest slot " + slot + " for " + playerKey
                            + "; valid slots are 0-" + (MAX_EXTRA_SLOTS - 1) + ".");
                    continue;
                }
                final String encoded = section.getString(slotKey);
                if (encoded == null || encoded.isBlank()) {
                    continue;
                }
                try {
                    final ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
                    if (item != null && !item.isEmpty()) {
                        items[slot] = item;
                        found = true;
                    }
                } catch (final RuntimeException exception) {
                    warn.accept("Ignored unreadable ender chest item in slot " + slot
                            + " for " + playerKey + ".");
                }
            }
            if (found) {
                extras.put(id, items);
            }
        }
    }

    /**
     * Returns a copy of all extra slots (up to 27). Callers should only expose slots up to
     * the player's current capacity.
     */
    public ItemStack[] extra(final UUID id) {
        final ItemStack[] stored = extras.get(id);
        final ItemStack[] copy = new ItemStack[MAX_EXTRA_SLOTS];
        if (stored != null) {
            for (int slot = 0; slot < MAX_EXTRA_SLOTS; slot++) {
                copy[slot] = cloneItem(stored[slot]);
            }
        }
        return copy;
    }

    /** Replaces the extra slots with a copy of {@code items}. Empty chests are dropped. */
    public void put(final UUID id, final ItemStack[] items) {
        final ItemStack[] copy = copyOf(items);
        if (hasItems(copy)) {
            extras.put(id, copy);
        } else {
            extras.remove(id);
        }
    }

    /**
     * Persists one player's extra slots and only then updates the in-memory map, so a failed
     * write leaves both the disk and the cache on the previous state.
     *
     * @return {@code false} when the file could not be written
     */
    public boolean commit(final UUID id, final ItemStack[] items, final Consumer<String> warn) {
        final ItemStack[] copy = copyOf(items);
        final Map<UUID, ItemStack[]> candidate = new LinkedHashMap<>(extras);
        if (hasItems(copy)) {
            candidate.put(id, copy);
        } else {
            candidate.remove(id);
        }
        if (!write(candidate, warn)) {
            return false;
        }
        if (hasItems(copy)) {
            extras.put(id, copy);
        } else {
            extras.remove(id);
        }
        return true;
    }

    public boolean save(final Consumer<String> warn) {
        return write(extras, warn);
    }

    private boolean write(final Map<UUID, ItemStack[]> source, final Consumer<String> warn) {
        final YamlConfiguration config = new YamlConfiguration();
        source.forEach((id, items) -> {
            for (int slot = 0; slot < MAX_EXTRA_SLOTS; slot++) {
                final ItemStack item = items[slot];
                if (item == null || item.isEmpty()) {
                    continue;
                }
                config.set(
                        "chests." + id + "." + slot,
                        Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            }
        });
        return file.save(config, warn);
    }

    private static ItemStack[] copyOf(final ItemStack[] items) {
        final ItemStack[] copy = new ItemStack[MAX_EXTRA_SLOTS];
        for (int slot = 0; slot < MAX_EXTRA_SLOTS && items != null && slot < items.length; slot++) {
            copy[slot] = cloneItem(items[slot]);
        }
        return copy;
    }

    private static boolean hasItems(final ItemStack[] items) {
        for (final ItemStack item : items) {
            if (item != null) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack cloneItem(final ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }
}
