package io.github.near.sweats.stats;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Keeps the {@code /stats} menu read-only: clicks and drags never move items. */
public final class StatsMenuListener implements Listener {

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof StatsMenu)) {
            return;
        }

        event.setCancelled(true);
        if (event.getRawSlot() == StatsMenu.CLOSE_SLOT && event.getWhoClicked() instanceof final Player player) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof StatsMenu) {
            event.setCancelled(true);
        }
    }
}
