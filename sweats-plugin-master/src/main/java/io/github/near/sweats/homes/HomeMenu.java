package io.github.near.sweats.homes;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.near.sweats.menu.ProtectedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The home menu. Slots above the current rank are shown as locked, never deleted. */
public final class HomeMenu implements ProtectedMenu {

    public static final int SIZE = 27;
    public static final int CLOSE_SLOT = 22;
    public static final int MAX_HOME_SLOTS = 7;

    private final UUID owner;
    private final int limit;
    private final Inventory inventory;

    public HomeMenu(final UUID owner, final int limit, final Map<Integer, Home> homes) {
        this.owner = owner;
        this.limit = limit;
        this.inventory = Bukkit.createInventory(
                this, SIZE, Component.text("Homes", NamedTextColor.DARK_GRAY));
        fill(homes);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID owner() {
        return owner;
    }

    @Override
    public UUID ownerId() {
        return owner;
    }

    public int limit() {
        return limit;
    }

    /** Returns the home slot number, or {@code -1} when the clicked raw slot is not a home. */
    public int homeSlot(final int rawSlot) {
        return rawSlot >= 0 && rawSlot < MAX_HOME_SLOTS ? rawSlot + 1 : -1;
    }

    public void open(final Player player) {
        player.openInventory(inventory);
    }

    private void fill(final Map<Integer, Home> homes) {
        final ItemStack spacer = icon(
                Material.BLACK_STAINED_GLASS_PANE,
                Component.text(" ", NamedTextColor.GRAY),
                List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }

        for (int slot = 1; slot <= MAX_HOME_SLOTS; slot++) {
            inventory.setItem(slot - 1, icon(slot, homes.get(slot)));
        }

        inventory.setItem(CLOSE_SLOT, icon(
                Material.BARRIER,
                Component.text("Close", NamedTextColor.RED),
                List.of(plain(Component.text("Click to close this menu.", NamedTextColor.DARK_GRAY)))));
    }

    private ItemStack icon(final int slot, final Home home) {
        if (slot > limit) {
            return icon(
                    Material.BARRIER,
                    Component.text("Home " + slot + " (locked)", NamedTextColor.RED),
                    List.of(
                            plain(Component.text("Locked at your current rank.", NamedTextColor.GRAY)),
                            plain(Component.text("Your saved home is kept.", NamedTextColor.DARK_GRAY))));
        }
        if (home == null) {
            return icon(
                    Material.LIGHT_GRAY_BED,
                    Component.text("Home " + slot, NamedTextColor.GRAY),
                    List.of(
                            plain(Component.text("Not set.", NamedTextColor.GRAY)),
                            plain(Component.text("Use /homeset " + slot + ".", NamedTextColor.DARK_GRAY))));
        }
        return icon(
                Material.RED_BED,
                Component.text("Home " + slot, NamedTextColor.WHITE),
                List.of(
                        plain(Component.text(
                                home.world() + " " + Math.round(home.x()) + " "
                                        + Math.round(home.y()) + " " + Math.round(home.z()),
                                NamedTextColor.AQUA)),
                        plain(Component.text("Click to teleport.", NamedTextColor.GREEN))));
    }

    private static ItemStack icon(
            final Material material, final Component name, final List<Component> lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(final Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
