package io.github.near.sweats.enderchest;

import io.github.near.sweats.perks.RankPerks;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Ender chest capacity by rank:
 * - Default, Grinder: 27 slots (vanilla only)
 * - Sweat: 36 slots (27 vanilla + 9 extra)
 * - Sweat+, Custom, Investor: 54 slots (27 vanilla + 27 extra)
 *
 * <p>The first 27 slots stay in the vanilla ender chest. Extra slots are persisted separately.
 * When a player's rank changes, the visible capacity is adjusted immediately. Items in slots
 * beyond the new capacity are preserved in storage but become inaccessible until the rank is
 * upgraded again. Storage is never destroyed by rank changes.
 *
 * <p>The real chest is updated after every click while the menu is open, and the whole menu is
 * flushed on close, quit, rank loss, reload and shutdown.
 */
public final class EnderChestService implements Listener, AutoCloseable {

    private final RankService ranks;
    private final Predicate<UUID> authenticated;
    private final Consumer<String> warn;
    private final EnderChestStore store;
    private final Map<UUID, EnderChestMenu> open = new HashMap<>();

    public EnderChestService(
            final Path folder,
            final RankService ranks,
            final Predicate<UUID> authenticated,
            final Consumer<String> warn) {
        this.ranks = Objects.requireNonNull(ranks, "ranks");
        this.authenticated = Objects.requireNonNull(authenticated, "authenticated");
        this.warn = Objects.requireNonNull(warn, "warn");
        this.store = new EnderChestStore(folder.resolve("enderchests.yml"));
        this.store.load(warn);
    }

    /** Returns the ender chest capacity in slots for the given player (27, 36, or 54). */
    public int capacity(final UUID id) {
        return RankPerks.of(ranks.rankOf(id)).enderChest().slots();
    }

    public void open(final Player player) {
        if (!authenticated.test(player.getUniqueId())) {
            player.sendMessage(error("You must log in first."));
            return;
        }

        final EnderChestMenu previous = open.remove(player.getUniqueId());
        if (previous != null) {
            if (!sync(previous)) {
                open.put(player.getUniqueId(), previous);
                player.sendMessage(error(
                        "Your ender chest could not be saved. Please close it and try again."));
                return;
            }
        }

        final int capacity = capacity(player.getUniqueId());
        final ItemStack[] contents = new ItemStack[EnderChestMenu.MAX_TOTAL_SLOTS];
        final Inventory vanilla = player.getEnderChest();
        for (int slot = 0; slot < EnderChestMenu.VANILLA_SLOTS; slot++) {
            contents[slot] = cloneItem(vanilla.getItem(slot));
        }
        final ItemStack[] extra = store.extra(player.getUniqueId());
        System.arraycopy(
                extra, 0, contents, EnderChestMenu.VANILLA_SLOTS, EnderChestStore.MAX_EXTRA_SLOTS);

        final EnderChestMenu menu = new EnderChestMenu(player.getUniqueId(), contents);
        menu.setVisibleCapacity(capacity);
        open.put(player.getUniqueId(), menu);
        menu.open(player);
        if (player.getOpenInventory().getTopInventory().getHolder() != menu) {
            open.remove(player.getUniqueId(), menu);
            player.sendMessage(error("Could not open your ender chest."));
        }
    }

    /** Flushes every open menu and re-reads the extra slots from disk. */
    public void reload() {
        flushAll();
        store.load(warn);
    }

    /** Called after a rank change; adjusts the visible capacity immediately. */
    public void rankChanged(final UUID id) {
        final EnderChestMenu menu = open.get(id);
        if (menu == null) {
            return;
        }
        final int newCapacity = capacity(id);
        final int oldCapacity = menu.getVisibleCapacity();
        if (newCapacity == oldCapacity) {
            return;
        }

        // Always sync the current state first to preserve all items in storage
        if (!sync(menu)) {
            // Keep the contents reachable so a later flush can retry instead of dropping items.
            open.put(id, menu);
            warn.accept("Could not save the ender chest of " + id + " after a rank change.");
            return;
        }

        // Update the visible capacity
        menu.setVisibleCapacity(newCapacity);

        final Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return;
        }
        if (newCapacity < oldCapacity) {
            player.sendMessage(error(
                    "Your ender chest capacity decreased to " + newCapacity + " slots because your rank changed."));
        } else {
            player.sendMessage(Component.text(
                    "Your ender chest capacity increased to " + newCapacity + " slots!", NamedTextColor.GREEN));
        }
        if (player.getOpenInventory().getTopInventory().getHolder() == menu) {
            // Re-open to apply the new capacity restriction
            player.closeInventory();
            menu.open(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.ENDER_CHEST) {
            return;
        }
        final Player player = event.getPlayer();
        final int capacity = capacity(player.getUniqueId());
        if (capacity <= EnderChestMenu.VANILLA_SLOTS) {
            return;
        }
        if (player.isSneaking()) {
            final ItemStack hand = player.getInventory().getItemInMainHand();
            if (!hand.isEmpty() && hand.getType().isBlock()) {
                // Let vanilla place the block against the chest instead of opening it.
                return;
            }
        }

        event.setCancelled(true);
        open(player);
    }

    /**
     * Mirrors slots 0-26 into the real ender chest as soon as the menu changes. The player cannot
     * access both inventories at the same time, so no duplicate can be created.
     * Also prevents accessing slots beyond the player's visible capacity.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(final InventoryClickEvent event) {
        if (event.isCancelled()
                || !(event.getView().getTopInventory().getHolder() instanceof final EnderChestMenu menu)) {
            return;
        }

        // Prevent interactions with slots beyond visible capacity
        final int rawSlot = event.getRawSlot();
        if (rawSlot >= menu.getVisibleCapacity()) {
            event.setCancelled(true);
            return;
        }

        // Also check cursor and other inventory interactions that could bypass slot restrictions
        if (event.getCursor() != null && !event.getCursor().isEmpty()) {
            // Allow cursor interactions only within visible capacity
            if (event.getSlot() >= menu.getVisibleCapacity()) {
                event.setCancelled(true);
                return;
            }
        }

        reportSync(menu);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(final InventoryDragEvent event) {
        if (event.isCancelled()
                || !(event.getView().getTopInventory().getHolder() instanceof final EnderChestMenu menu)) {
            return;
        }

        // Prevent drag operations involving slots beyond visible capacity
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot >= menu.getVisibleCapacity()) {
                event.setCancelled(true);
                return;
            }
        }

        reportSync(menu);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof final EnderChestMenu menu)) {
            return;
        }
        if (open.remove(menu.owner(), menu) && !sync(menu)) {
            // The menu is no longer displayed but still holds the only copy of the change.
            open.put(menu.owner(), menu);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final EnderChestMenu menu = open.remove(event.getPlayer().getUniqueId());
        if (menu != null && !sync(menu)) {
            open.put(menu.owner(), menu);
        }
    }

    @Override
    public void close() {
        flushAll();
    }

    private void flushAll() {
        if (open.isEmpty()) {
            return;
        }
        // Clear first: closing fires InventoryCloseEvent, which must not save the same menu twice.
        final List<EnderChestMenu> menus = new ArrayList<>(open.values());
        open.clear();
        for (final EnderChestMenu menu : menus) {
            if (!sync(menu)) {
                open.put(menu.owner(), menu);
            }
            closeView(menu);
        }
    }

    /** Closes a menu that is no longer tracked, so the player cannot keep editing stale contents. */
    private static void closeView(final EnderChestMenu menu) {
        final Player player = Bukkit.getPlayer(menu.owner());
        if (player != null
                && player.getOpenInventory().getTopInventory().getHolder() == menu) {
            player.closeInventory();
        }
    }

    /** Writes the menu's extra slots (up to visible capacity) and only mirrors vanilla slots once the write succeeded. */
    private boolean sync(final EnderChestMenu menu) {
        final Player player = Bukkit.getPlayer(menu.owner());
        // Persist all extra slots (up to 27) to preserve items beyond visible capacity
        final ItemStack[] extraToPersist = menu.extraContents();
        if (player == null) {
            return store.commit(menu.owner(), extraToPersist, warn);
        }
        if (!store.commit(menu.owner(), extraToPersist, warn)) {
            return false;
        }
        final Inventory vanilla = player.getEnderChest();
        final ItemStack[] vanillaContents = menu.vanillaContents();
        for (int slot = 0; slot < EnderChestMenu.VANILLA_SLOTS; slot++) {
            vanilla.setItem(slot, cloneItem(vanillaContents[slot]));
        }
        return true;
    }

    private void reportSync(final EnderChestMenu menu) {
        if (sync(menu)) {
            return;
        }
        final Player player = Bukkit.getPlayer(menu.owner());
        if (player != null) {
            player.sendMessage(error(
                    "Your ender chest could not be saved. Your previous contents were kept."));
        }
    }

    private static ItemStack cloneItem(final ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }

    private static Component error(final String message) {
        return Component.text(message, NamedTextColor.RED);
    }
}
