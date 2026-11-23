package io.github.near.sweats.items;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Identity tags for custom, non-fungible items (runes, applied equipment, the Admin Sword).
 *
 * <p>Plain materials deliberately carry no tag: two stacks of diamonds are the same material and
 * merging or splitting them is normal. A tagged item is unique, so seeing the same identity twice
 * or seeing one in a stack larger than one is evidence of item duplication.
 */
public final class ItemIdentity {

    private static final String KEY = "unique_id";

    private ItemIdentity() {}

    public static NamespacedKey key(final Plugin plugin) {
        return new NamespacedKey(plugin, KEY);
    }

    /** Gives a new item a fresh identity. */
    public static void assign(final Plugin plugin, final ItemStack item) {
        write(plugin, item, UUID.randomUUID().toString());
    }

    /** Adds an identity only when the item does not have one yet. */
    public static void ensure(final Plugin plugin, final ItemStack item) {
        if (read(plugin, item).isEmpty()) {
            assign(plugin, item);
        }
    }

    public static Optional<UUID> read(final Plugin plugin, final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        final String stored = meta.getPersistentDataContainer()
                .get(key(plugin), PersistentDataType.STRING);
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(stored));
        } catch (final IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public static Optional<String> raw(final Plugin plugin, final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        final String stored = meta.getPersistentDataContainer()
                .get(key(plugin), PersistentDataType.STRING);
        return stored == null || stored.isBlank() ? Optional.empty() : Optional.of(stored);
    }

    private static void write(final Plugin plugin, final ItemStack item, final String value) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, value);
        item.setItemMeta(meta);
    }
}
