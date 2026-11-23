package io.github.near.sweats.stats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import io.github.near.sweats.menu.ProtectedMenu;

/**
 * The chest menu behind {@code /stats}: three rows of icons, one per statistic.
 *
 * <p>A fresh menu is built for every viewer, so two staff members can look at the same player at
 * the same time without sharing an inventory.
 */
public final class StatsMenu implements ProtectedMenu {

    public static final int SIZE = 27;
    public static final int CLOSE_SLOT = 22;

    private static final int SLOT_HEAD = 9;
    private static final int SLOT_FIRST_PLAYED = 10;
    private static final int SLOT_PLAYTIME = 11;
    private static final int SLOT_DISTANCE = 12;
    private static final int SLOT_MINED = 13;
    private static final int SLOT_PLACED = 14;
    private static final int SLOT_USED = 15;
    private static final int SLOT_KILLS = 16;
    private static final int SLOT_DEATHS = 17;

    private final Inventory inventory;

    public StatsMenu(final StatsSnapshot stats, final OfflinePlayer target) {
        this.inventory = Bukkit.createInventory(
                this, SIZE, Component.text("Stats: " + stats.name(), NamedTextColor.DARK_GRAY));
        fill(stats, target);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(final Player viewer) {
        viewer.openInventory(inventory);
    }

    private void fill(final StatsSnapshot stats, final OfflinePlayer target) {
        final ItemStack spacer = icon(Material.BLACK_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.GRAY));
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }

        inventory.setItem(SLOT_HEAD, head(stats, target));
        inventory.setItem(SLOT_FIRST_PLAYED, firstPlayed(stats));
        inventory.setItem(SLOT_PLAYTIME, playtime(stats));
        inventory.setItem(SLOT_DISTANCE, distance(stats));
        inventory.setItem(SLOT_MINED, mined(stats));
        inventory.setItem(SLOT_PLACED, placed(stats));
        inventory.setItem(SLOT_USED, used(stats));
        inventory.setItem(SLOT_KILLS, kills(stats));
        inventory.setItem(SLOT_DEATHS, deaths(stats));

        inventory.setItem(CLOSE_SLOT, icon(
                Material.BARRIER,
                Component.text("Close", NamedTextColor.RED),
                Component.text("Click to close this menu.", NamedTextColor.DARK_GRAY)));
    }

    // ------------------------------------------------------------------------------------------
    // Icons

    private static ItemStack head(final StatsSnapshot stats, final OfflinePlayer target) {
        final ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        final ItemMeta meta = item.getItemMeta();
        if (meta instanceof final SkullMeta skull) {
            skull.setOwningPlayer(target);
        }
        meta.displayName(plain(Component.text(stats.name(), NamedTextColor.WHITE)));

        final List<Component> lore = new ArrayList<>();
        lore.add(plain(stats.online()
                ? Component.text("● Online", NamedTextColor.GREEN)
                : Component.text("● Offline", NamedTextColor.RED)));
        lore.add(blank());
        lore.add(plain(Component.text("UUID", NamedTextColor.DARK_GRAY)));
        lore.add(plain(Component.text(stats.uuid().toString(), NamedTextColor.GRAY)));
        if (stats.online()) {
            lore.add(blank());
            lore.add(plain(Component.text("Ping: ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(stats.ping() + " ms", NamedTextColor.GRAY))));
            if (stats.world() != null) {
                lore.add(plain(Component.text("Location: ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(
                                stats.world() + " " + stats.x() + " " + stats.y() + " " + stats.z(),
                                NamedTextColor.GRAY))));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack firstPlayed(final StatsSnapshot stats) {
        final List<Component> lore = new ArrayList<>();
        if (stats.firstPlayed() <= 0L) {
            lore.add(plain(Component.text("Unknown", NamedTextColor.GRAY)));
        } else {
            lore.add(plain(Component.text(StatsFormat.date(stats.firstPlayed()), NamedTextColor.AQUA)));
            lore.add(plain(Component.text(
                    StatsFormat.relative(stats.firstPlayed(), Instant.now()), NamedTextColor.GRAY)));
        }
        lore.add(blank());
        lore.add(plain(Component.text("Last seen", NamedTextColor.DARK_GRAY)));
        lore.add(plain(Component.text(
                stats.lastPlayed() <= 0L ? "Unknown" : StatsFormat.date(stats.lastPlayed()),
                NamedTextColor.GRAY)));

        return icon(Material.OAK_SIGN, Component.text("First played", NamedTextColor.WHITE), lore);
    }

    private static ItemStack playtime(final StatsSnapshot stats) {
        return icon(
                Material.CLOCK,
                Component.text("Playtime", NamedTextColor.WHITE),
                value(stats, StatsFormat.duration(stats.playtimeTicks())),
                note("Total time on this server."));
    }

    private static ItemStack distance(final StatsSnapshot stats) {
        return icon(
                Material.LEATHER_BOOTS,
                Component.text("Distance travelled", NamedTextColor.WHITE),
                value(stats, StatsFormat.distance(stats.distanceCm())),
                note("Walking, swimming, flying and vehicles."));
    }

    private static ItemStack mined(final StatsSnapshot stats) {
        return icon(
                Material.IRON_PICKAXE,
                Component.text("Blocks mined", NamedTextColor.WHITE),
                value(stats, StatsFormat.count(stats.blocksMined())),
                note("Every block broken."));
    }

    private static ItemStack placed(final StatsSnapshot stats) {
        // Tracked by this plugin, so it is readable even while the player is offline.
        return icon(
                Material.BRICKS,
                Component.text("Blocks placed", NamedTextColor.WHITE),
                Component.text(StatsFormat.count(stats.blocksPlaced()), NamedTextColor.AQUA),
                note("Counted since Sweats was installed."));
    }

    private static ItemStack used(final StatsSnapshot stats) {
        return icon(
                Material.BUNDLE,
                Component.text("Items used", NamedTextColor.WHITE),
                value(stats, StatsFormat.count(stats.itemsUsed())),
                note("Includes blocks placed."));
    }

    private static ItemStack kills(final StatsSnapshot stats) {
        return icon(
                Material.NETHERITE_SWORD,
                Component.text("Kills", NamedTextColor.WHITE),
                value(stats, "Total: " + StatsFormat.count(stats.totalKills())),
                note("Players: " + (stats.hasVanillaStats() ? StatsFormat.count(stats.playerKills()) : "?")),
                note("Mobs: " + (stats.hasVanillaStats() ? StatsFormat.count(stats.mobKills()) : "?")));
    }

    private static ItemStack deaths(final StatsSnapshot stats) {
        final String ratio = stats.hasVanillaStats() && stats.deaths() > 0
                ? String.format(Locale.US, "%.2f", stats.killDeathRatio())
                : "—";
        return icon(
                Material.TOTEM_OF_UNDYING,
                Component.text("Deaths", NamedTextColor.WHITE),
                value(stats, StatsFormat.count(stats.deaths())),
                note("K/D: " + ratio));
    }

    // ------------------------------------------------------------------------------------------
    // Small component helpers

    private static Component value(final StatsSnapshot stats, final String formatted) {
        return stats.hasVanillaStats()
                ? Component.text(formatted, NamedTextColor.AQUA)
                : Component.text("Player must be online", NamedTextColor.RED);
    }

    private static Component note(final String text) {
        return Component.text(text, NamedTextColor.DARK_GRAY);
    }

    private static Component blank() {
        return Component.empty();
    }

    private static ItemStack icon(
            final Material material, final Component name, final Component... lore) {
        return icon(material, name, List.of(lore));
    }

    private static ItemStack icon(
            final Material material, final Component name, final List<Component> lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name));
        meta.lore(lore.stream().map(StatsMenu::plain).toList());
        item.setItemMeta(meta);
        return item;
    }

    /** Items in inventory GUIs default to italic; strip that so the text looks like a real menu. */
    private static Component plain(final Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
