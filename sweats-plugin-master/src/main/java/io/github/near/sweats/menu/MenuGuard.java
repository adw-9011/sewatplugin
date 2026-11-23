package io.github.near.sweats.menu;

import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * Server-side validation shared by every custom menu.
 *
 * <p>It runs before the feature listeners. Read-only menus reject every click, drag and offhand
 * swap; mutable menus still reject a viewer who does not own the inventory and raw slots outside
 * the window. Shift-clicks, number-key swaps and hotbar swaps are all inventory click actions, so
 * they pass through the same check. A close by a non-owner is reported as suspicious.
 */
public final class MenuGuard implements Listener {

    private final Consumer<String> suspicious;

    public MenuGuard(final Consumer<String> suspicious) {
        this.suspicious = suspicious == null ? ignored -> {} : suspicious;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(final InventoryClickEvent event) {
        final ProtectedMenu menu = menu(event.getView());
        if (menu == null) {
            return;
        }
        if (!menu.ownedBy(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            report(menu, event.getWhoClicked().getName(), "interaction by non-owner");
            return;
        }
        if (!menu.mutable()) {
            event.setCancelled(true);
        }
        if (!insideWindow(event.getView(), event.getRawSlot())) {
            event.setCancelled(true);
            report(menu, event.getWhoClicked().getName(), "raw slot outside the window");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(final InventoryDragEvent event) {
        final ProtectedMenu menu = menu(event.getView());
        if (menu == null) {
            return;
        }
        if (!menu.ownedBy(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            report(menu, event.getWhoClicked().getName(), "drag by non-owner");
            return;
        }
        if (!menu.mutable()) {
            event.setCancelled(true);
        }
        for (final int slot : event.getRawSlots()) {
            if (!insideWindow(event.getView(), slot)) {
                event.setCancelled(true);
                report(menu, event.getWhoClicked().getName(), "drag slot outside the window");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
        final ProtectedMenu menu = menu(event.getPlayer().getOpenInventory());
        if (menu != null && !menu.mutable()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        final ProtectedMenu menu = menu(event.getView());
        if (menu == null) {
            return;
        }
        if (event.getPlayer() instanceof final Player player
                && !menu.ownedBy(player.getUniqueId())) {
            report(menu, player.getName(), "closed a menu owned by someone else");
        }
    }

    private static ProtectedMenu menu(final InventoryView view) {
        if (view == null) {
            return null;
        }
        final Inventory top = view.getTopInventory();
        return top.getHolder() instanceof final ProtectedMenu menu ? menu : null;
    }

    private static boolean insideWindow(final InventoryView view, final int rawSlot) {
        if (rawSlot < 0) {
            return false;
        }
        final int size = view.getTopInventory().getSize() + view.getBottomInventory().getSize();
        return rawSlot < size;
    }

    private void report(final ProtectedMenu menu, final String player, final String reason) {
        suspicious.accept("menu-guard: " + reason
                + " player=" + player
                + " menu=" + menu.getClass().getSimpleName());
    }
}
