package io.github.near.sweats.runes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Rune drops from the Warden, Wither, Ender Dragon and Elder Guardian. */
public final class RuneDrops implements Listener {

    private static final Map<EntityType, String> BOSSES = Map.of(
            EntityType.WARDEN, "warden",
            EntityType.WITHER, "wither",
            EntityType.ENDER_DRAGON, "dragon",
            EntityType.ELDER_GUARDIAN, "elder_guardian");

    private static final Map<String, Double> DEFAULT_CHANCE = Map.of(
            "warden", 0.104,
            "wither", 0.11,
            "dragon", 0.115,
            "elder_guardian", 0.119);

    private final JavaPlugin plugin;
    private final RuneItems items;
    private final RuneAccessStore access;
    private final NamespacedKey killsKey;

    /** Per-player luck multiplier for testing (in memory only, resets on restart). */
    private final Map<UUID, Double> luck = new HashMap<>();

    public RuneDrops(
            final JavaPlugin plugin, final RuneItems items, final RuneAccessStore access) {
        this.plugin = plugin;
        this.items = items;
        this.access = access;
        this.killsKey = new NamespacedKey(plugin, "boss_kills");
    }

    public static boolean isBoss(final EntityType type) {
        return BOSSES.containsKey(type);
    }

    public void setLuck(final UUID id, final double multiplier) {
        if (multiplier <= 1.0) {
            luck.remove(id);
        } else {
            luck.put(id, multiplier);
        }
    }

    public double luck(final UUID id) {
        return luck.getOrDefault(id, 1.0);
    }

    /** Testing multipliers are per-session state and must not leak across logins. */
    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        luck.remove(event.getPlayer().getUniqueId());
    }

    public void close() {
        luck.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final String key = BOSSES.get(event.getEntity().getType());
        if (key == null) {
            return;
        }
        final Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }

        // Count this boss kill.
        final PersistentDataContainer data = killer.getPersistentDataContainer();
        final int kills = data.getOrDefault(killsKey, PersistentDataType.INTEGER, 0) + 1;
        data.set(killsKey, PersistentDataType.INTEGER, kills);

        final double base = plugin.getConfig()
                .getDouble("runes.drops.chance." + key, DEFAULT_CHANCE.get(key));
        final double multiplier = plugin.getConfig().getDouble("runes.drops.rarity-multiplier", 0.9);
        final double luckMultiplier = luck(killer.getUniqueId());
        final double maxBonus = plugin.getConfig().getDouble("runes.drops.max-perm-bonus", 5.0);
        final double bonus = Math.min(access.bonus(killer.getUniqueId()), maxBonus);
        final ThreadLocalRandom random = ThreadLocalRandom.current();

        // Rarest first; at most one rune per kill.
        final Rune.Rarity[] rarities = Rune.Rarity.values();
        for (int index = rarities.length - 1; index >= 0; index--) {
            final double chance = Math.min(
                    100.0,
                    (base + bonus) * Math.pow(multiplier, index) * luckMultiplier);
            if (random.nextDouble() * 100.0 >= chance) {
                continue;
            }

            final Rune.Rarity rarity = rarities[index];
            final List<Rune> pool =
                    RuneCatalog.all().stream().filter(rune -> rune.rarity() == rarity).toList();
            if (pool.isEmpty()) {
                continue;
            }
            final Rune rune = pool.get(random.nextInt(pool.size()));
            final int tier = rollTier(random.nextDouble() * 100.0);

            final ItemStack item = items.runeItem(rune, tier);
            killer.getInventory()
                    .addItem(item)
                    .values()
                    .forEach(dropped -> killer.getWorld()
                            .dropItemNaturally(killer.getLocation(), dropped));
            announce(killer, rune, tier, item, kills);
            return;
        }
    }

    private int rollTier(final double roll) {
        final double tierOne = plugin.getConfig().getDouble("runes.drops.tier-weights.t1", 80);
        final double tierTwo = plugin.getConfig().getDouble("runes.drops.tier-weights.t2", 15);
        if (roll < tierOne) {
            return 1;
        }
        if (roll < tierOne + tierTwo) {
            return 2;
        }
        return 3;
    }

    private void announce(
            final Player player,
            final Rune rune,
            final int tier,
            final ItemStack item,
            final int kills) {
        final Component name = Component.text(
                        rune.name() + " Rune " + RuneItems.roman(tier), rune.rarity().color)
                .decoration(TextDecoration.BOLD, true)
                .hoverEvent(item.asHoverEvent());
        final Component message = Component.text("RUNE DROP! ", rune.rarity().color)
                .decoration(TextDecoration.BOLD, true)
                .append(Component.text(player.getName(), NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text(" dropped ", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false))
                .append(name)
                .append(Component.text(
                                " (" + rune.rarity().name() + ") in ", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text(kills, NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text(
                                kills == 1 ? " boss kill!" : " boss kills!", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false));
        Bukkit.broadcast(message);
    }
}
