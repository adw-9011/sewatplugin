package io.github.near.sweats.menu;

import java.util.UUID;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker for every inventory Sweats opens itself. The guard uses the holder rather than the
 * window title, so a player cannot impersonate a menu with a renamed chest.
 */
public interface ProtectedMenu extends InventoryHolder {

    /** The only player allowed to interact, or null when any viewer may (read-only staff menus). */
    default UUID ownerId() {
        return null;
    }

    /** True when clicks are expected to move items, as in the ender chest and Runic Table. */
    default boolean mutable() {
        return false;
    }

    default boolean ownedBy(final UUID id) {
        final UUID owner = ownerId();
        return owner == null || owner.equals(id);
    }
}
