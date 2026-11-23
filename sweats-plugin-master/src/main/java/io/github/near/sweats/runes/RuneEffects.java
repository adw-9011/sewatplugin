package io.github.near.sweats.runes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/** Plays rune effects on hits, arrows, kills and worn armour. */
public final class RuneEffects implements Listener {

    private static final List<Rune> WEAPON_RUNES = RuneCatalog.all().stream()
            .filter(rune -> rune.equip() == Rune.Equip.WEAPON)
            .toList();

    private final JavaPlugin plugin;
    private final RuneItems items;
    private final Map<UUID, Location> lastPositions = new HashMap<>();
    private final BukkitTask armorTask;
    private int tick;

    public RuneEffects(final JavaPlugin plugin, final RuneItems items) {
        this.plugin = plugin;
        this.items = items;
        this.armorTask = startArmorTask();
    }

    // ------------------------------------------------------------------ melee

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof final Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof final LivingEntity victim)) {
            return;
        }
        final ItemStack hand = player.getInventory().getItemInMainHand();
        final Location at = victim.getLocation().add(0, victim.getHeight() / 2, 0);

        // Admin sword: every weapon rune at once, but only for the player it was issued to.
        if (items.isAdminSword(hand, player.getUniqueId())) {
            for (final Rune rune : WEAPON_RUNES) {
                rune.effect().play(at, RuneItems.MAX_TIER, 0);
            }
            return;
        }

        final RuneItems.Held held = items.applied(hand);
        if (held == null || !held.rune().equip().melee()) {
            return;
        }
        for (int burst = 0; burst < 4; burst++) {
            held.rune().effect().play(at, held.tier(), burst * 3);
        }
    }

    // ------------------------------------------------------------------ admin sword

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAdminHit(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof final Player player)) {
            return;
        }
        if (!items.isAdminSword(
                player.getInventory().getItemInMainHand(), player.getUniqueId())) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof final ComplexEntityPart part) {
            entity = part.getParent();
        }
        if (!RuneDrops.isBoss(entity.getType())) {
            return;
        }
        if (entity instanceof final Wither wither) {
            wither.setInvulnerableTicks(0);
        }
        // Never undo another plugin's cancellation; this handler only runs for events that are
        // still allowed.
        event.setDamage(1_000_000.0);
    }

    // ------------------------------------------------------------------ lightning

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        final ItemStack hand = killer.getInventory().getItemInMainHand();
        final RuneItems.Held held = items.applied(hand);
        final boolean lightning = items.isAdminSword(hand, killer.getUniqueId())
                || (held != null && held.rune().id().equals("lightning"));
        if (!lightning) {
            return;
        }
        final Location location = event.getEntity().getLocation();
        location.getWorld().strikeLightningEffect(location);
    }

    // ------------------------------------------------------------------ bows

    @EventHandler(ignoreCancelled = true)
    public void onShoot(final EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        final ItemStack bow = event.getBow();
        final RuneItems.Held held = items.applied(bow);
        if (held == null || held.rune().equip() != Rune.Equip.BOW) {
            return;
        }

        final Projectile projectile = (Projectile) event.getProjectile();
        new BukkitRunnable() {
            private int trailTick;

            @Override
            public void run() {
                final boolean stuck =
                        projectile instanceof final AbstractArrow arrow && arrow.isInBlock();
                if (!projectile.isValid() || stuck || trailTick > 200) {
                    cancel();
                    return;
                }
                held.rune().effect().play(projectile.getLocation(), held.tier(), trailTick++);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ------------------------------------------------------------------ armour

    private BukkitTask startArmorTask() {
        return new BukkitRunnable() {
            @Override
            public void run() {
                tick++;
                for (final Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                        continue;
                    }
                    final Location current = player.getLocation();
                    final Location previous = lastPositions.put(player.getUniqueId(), current);
                    final PlayerInventory inventory = player.getInventory();

                    // Boots only while moving.
                    final RuneItems.Held boots = items.applied(inventory.getBoots());
                    if (boots != null
                            && boots.rune().equip() == Rune.Equip.BOOTS
                            && previous != null
                            && previous.getWorld() == current.getWorld()
                            && previous.distanceSquared(current) > 0.0004) {
                        boots.rune().effect().play(current, boots.tier(), tick);
                    }

                    final RuneItems.Held chest = items.applied(inventory.getChestplate());
                    if (chest != null && chest.rune().equip() == Rune.Equip.CHESTPLATE) {
                        chest.rune().effect().play(current, chest.tier(), tick);
                    }

                    final RuneItems.Held helmet = items.applied(inventory.getHelmet());
                    if (helmet != null && helmet.rune().equip() == Rune.Equip.HELMET) {
                        helmet.rune().effect().play(current, helmet.tier(), tick);
                    }
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        lastPositions.remove(event.getPlayer().getUniqueId());
    }

    public void close() {
        armorTask.cancel();
        lastPositions.clear();
    }
}
