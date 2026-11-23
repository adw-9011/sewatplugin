package io.github.near.sweats.runes;

import io.github.near.sweats.menu.ProtectedMenu;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** The rune browser ({@code /runes}) and Runic Table ({@code /runetable}) menus. */
public final class RuneGui implements Listener {

    private static final int PER_PAGE = 45;
    private static final int IN_A = 11;
    private static final int OUT = 13;
    private static final int IN_B = 15;

    private final JavaPlugin plugin;
    private final RuneItems items;
    private final PermissionService permissions;
    private Predicate<Player> tableAccess = player -> false;

    public RuneGui(
            final JavaPlugin plugin,
            final RuneItems items,
            final PermissionService permissions) {
        this.plugin = plugin;
        this.items = items;
        this.permissions = permissions;
    }

    /** Wired after construction because the table needs this GUI to open itself. */
    public void setTableAccess(final Predicate<Player> tableAccess) {
        this.tableAccess = tableAccess == null ? player -> false : tableAccess;
    }

    /** Both the browser opener and every click on an already-open browser enforce this. */
    private boolean canBrowse(final Player player) {
        return permissions.has(player, PermissionNodes.RUNES)
                || permissions.has(player, PermissionNodes.RUNES_ADMIN);
    }

    // ------------------------------------------------------------------ holders

    public static final class Browser implements ProtectedMenu {
        private final UUID owner;
        private final int page;
        private Inventory inventory;

        private Browser(final UUID owner, final int page) {
            this.owner = owner;
            this.page = page;
        }

        @Override
        public UUID ownerId() {
            return owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class Table implements ProtectedMenu {
        private final UUID owner;
        private Inventory inventory;

        private Table(final UUID owner) {
            this.owner = owner;
        }

        @Override
        public UUID ownerId() {
            return owner;
        }

        @Override
        public boolean mutable() {
            return true;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ItemStack named(
            final Material material, final String name, final NamedTextColor color) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        final ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    private static void give(final Player player, final ItemStack item) {
        player.getInventory()
                .addItem(item)
                .values()
                .forEach(dropped -> player.getWorld().dropItemNaturally(player.getLocation(), dropped));
    }

    private static Component lore(final String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------ rune browser

    public void openBrowser(final Player player, final int requestedPage) {
        final List<Rune> runes = RuneCatalog.all();
        final int pages = (runes.size() + PER_PAGE - 1) / PER_PAGE;
        final int page = Math.max(0, Math.min(pages - 1, requestedPage));
        final Browser holder = new Browser(player.getUniqueId(), page);
        final Inventory inventory = Bukkit.createInventory(
                holder, 54, Component.text("Runes - page " + (page + 1) + "/" + pages));
        holder.inventory = inventory;

        final int start = page * PER_PAGE;
        for (int index = 0; index < PER_PAGE && start + index < runes.size(); index++) {
            final ItemStack icon = items.runeItem(runes.get(start + index), 1);
            final ItemMeta meta = icon.getItemMeta();
            final List<Component> lore = new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(Component.text("Left-click: get Tier I", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Right-click: get Tier II", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Shift-click: get Tier III", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inventory.setItem(index, icon);
        }
        for (int index = PER_PAGE; index < 54; index++) {
            inventory.setItem(index, filler());
        }
        if (page > 0) {
            inventory.setItem(45, named(Material.ARROW, "Previous page", NamedTextColor.GREEN));
        }
        if (page < pages - 1) {
            inventory.setItem(53, named(Material.ARROW, "Next page", NamedTextColor.GREEN));
        }
        inventory.setItem(49, named(Material.BOOK, runes.size() + " runes", NamedTextColor.AQUA));
        inventory.setItem(
                48,
                named(Material.ENCHANTING_TABLE, "Open Runic Table", NamedTextColor.LIGHT_PURPLE));
        player.openInventory(inventory);
    }

    private void onBrowserClick(
            final InventoryClickEvent event, final Browser browser, final Player player) {
        event.setCancelled(true);
        if (!canBrowse(player)) {
            // Revoking sweats.runes must stop grants even in an inventory that was opened
            // while the player still had access.
            player.closeInventory();
            player.sendMessage(Component.text(
                    "You no longer have access to the rune browser.", NamedTextColor.RED));
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        final int slot = event.getRawSlot();
        final int pages = (RuneCatalog.all().size() + PER_PAGE - 1) / PER_PAGE;

        if (slot == 45 && browser.page > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> openBrowser(player, browser.page - 1));
        } else if (slot == 53 && browser.page < pages - 1) {
            Bukkit.getScheduler().runTask(plugin, () -> openBrowser(player, browser.page + 1));
        } else if (slot == 48) {
            if (!tableAccess.test(player)) {
                player.sendMessage(Component.text(
                        "You don't have access to the Rune Table.", NamedTextColor.RED));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> openTable(player));
        } else if (slot < PER_PAGE) {
            final RuneItems.Held held = items.asRune(event.getCurrentItem());
            if (held == null) {
                return;
            }
            final int tier = event.isShiftClick()
                    ? 3
                    : (event.getClick() == ClickType.RIGHT ? 2 : 1);
            give(player, items.runeItem(held.rune(), tier));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        }
    }

    // ------------------------------------------------------------------ runic table

    public void openTable(final Player player) {
        final Table holder = new Table(player.getUniqueId());
        final Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Runic Table"));
        holder.inventory = inventory;
        for (int index = 0; index < 27; index++) {
            if (index != IN_A && index != IN_B && index != OUT) {
                inventory.setItem(index, filler());
            }
        }
        inventory.setItem(IN_A, null);
        inventory.setItem(IN_B, null);
        inventory.setItem(22, named(Material.PAPER, "How it works", NamedTextColor.AQUA));
        final ItemMeta meta = inventory.getItem(22).getItemMeta();
        meta.lore(List.of(
                lore("Rune + Rune (same, same tier) = next tier"),
                lore("Rune + Equipment = apply the rune"),
                lore("Equipment with a rune alone = remove it")));
        inventory.getItem(22).setItemMeta(meta);
        update(inventory);
        player.openInventory(inventory);
    }

    /** All recipe logic lives here. Returns {@code null} when there is no valid result. */
    ItemStack compute(final ItemStack first, final ItemStack second) {
        final boolean firstEmpty = RuneItems.empty(first);
        final boolean secondEmpty = RuneItems.empty(second);
        if (firstEmpty && secondEmpty) {
            return null;
        }

        final RuneItems.Held firstRune = items.asRune(first);
        final RuneItems.Held secondRune = items.asRune(second);

        if (!firstEmpty && !secondEmpty) {
            // rune + rune -> combine
            if (firstRune != null && secondRune != null) {
                if (firstRune.rune() == secondRune.rune()
                        && firstRune.tier() == secondRune.tier()
                        && firstRune.tier() < RuneItems.MAX_TIER) {
                    return items.runeItem(firstRune.rune(), firstRune.tier() + 1);
                }
                return null;
            }
            // rune + equipment -> apply
            final RuneItems.Held rune = firstRune != null ? firstRune : secondRune;
            final ItemStack gear = firstRune != null ? second : first;
            if (rune == null || items.asRune(gear) != null) {
                return null;
            }
            if (gear.getAmount() != 1) {
                return null;
            }
            if (!rune.rune().equip().matches(gear.getType())) {
                return null;
            }
            return items.applyRune(gear, rune.rune(), rune.tier());
        }

        // single equipment holding a rune -> remove it
        final ItemStack only = firstEmpty ? second : first;
        if (items.asRune(only) == null
                && items.applied(only) != null
                && only.getAmount() == 1) {
            return items.removeRune(only);
        }
        return null;
    }

    private void update(final Inventory inventory) {
        final ItemStack result = compute(inventory.getItem(IN_A), inventory.getItem(IN_B));
        if (result != null) {
            inventory.setItem(OUT, result);
        } else {
            inventory.setItem(OUT, named(Material.BARRIER, "No valid recipe", NamedTextColor.RED));
        }
    }

    private void scheduleUpdate(final Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> update(inventory));
    }

    private void onTableClick(
            final InventoryClickEvent event, final Table table, final Player player) {
        final Inventory top = table.inventory;
        final int raw = event.getRawSlot();
        final boolean inTop = raw < top.getSize();

        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }

        if (!inTop) {
            // shift-click from player inventory -> manually route into an input slot
            if (event.isShiftClick()) {
                event.setCancelled(true);
                final ItemStack current = event.getCurrentItem();
                if (RuneItems.empty(current)) {
                    return;
                }
                for (final int slot : new int[] {IN_A, IN_B}) {
                    if (RuneItems.empty(top.getItem(slot))) {
                        top.setItem(slot, current.clone());
                        event.setCurrentItem(null);
                        break;
                    }
                }
            }
            scheduleUpdate(top);
            return;
        }

        if (raw == OUT) {
            event.setCancelled(true);
            final ItemStack result = compute(top.getItem(IN_A), top.getItem(IN_B));
            if (result == null) {
                return;
            }
            if (event.isShiftClick()) {
                give(player, result);
            } else {
                if (!RuneItems.empty(player.getItemOnCursor())) {
                    return;
                }
                player.setItemOnCursor(result);
            }
            top.setItem(IN_A, null);
            top.setItem(IN_B, null);
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
            update(top);
            return;
        }

        if (raw == IN_A || raw == IN_B) {
            // vanilla behaviour is fine here; just refresh the output afterwards
            scheduleUpdate(top);
            return;
        }

        event.setCancelled(true); // filler / info
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof final Player player)) {
            return;
        }
        final InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof final Browser browser) {
            onBrowserClick(event, browser, player);
        } else if (holder instanceof final Table table) {
            onTableClick(event, table, player);
        }
    }

    @EventHandler
    public void onDrag(final InventoryDragEvent event) {
        final InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof Browser) {
            event.setCancelled(true);
        } else if (holder instanceof final Table table) {
            final int size = table.inventory.getSize();
            for (final int slot : event.getRawSlots()) {
                if (slot < size && slot != IN_A && slot != IN_B) {
                    event.setCancelled(true);
                    return;
                }
            }
            scheduleUpdate(table.inventory);
        }
    }

    @EventHandler
    public void onClose(final InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof final Table table)) {
            return;
        }
        if (!(event.getPlayer() instanceof final Player player)) {
            return;
        }
        for (final int slot : new int[] {IN_A, IN_B}) {
            final ItemStack item = table.inventory.getItem(slot);
            if (!RuneItems.empty(item)) {
                give(player, item);
            }
            table.inventory.setItem(slot, null);
        }
    }
}
