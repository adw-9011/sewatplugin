package io.github.near.sweats.homes;

import io.github.near.sweats.perks.RankPerks;
import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.teleport.TeleportSafety;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Saved homes, rank-limited slots, cooldowns, and the shared warmup scheduler.
 *
 * <p>Stored homes are never trimmed when a rank is removed. The GUI and command simply refuse
 * slots above the current limit, so restoring the rank restores access to the original data.
 *
 * <p>Cooldowns are tracked per player and only applied after a successful teleport.
 * The cooldown duration is determined by the player's current rank.
 */
public final class HomeService implements Listener, AutoCloseable {

    private static final long TICK_MILLIS = 500L;

    private final Plugin plugin;
    private final RankService ranks;
    private final Predicate<UUID> authenticated;
    private final Consumer<String> warn;
    private final HomeStore store;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private TeleportSafety safety;
    private int warmupSeconds;
    private BukkitTask ticker;

    public HomeService(
            final Plugin plugin,
            final Path folder,
            final RankService ranks,
            final Predicate<UUID> authenticated,
            final Consumer<String> warn) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ranks = Objects.requireNonNull(ranks, "ranks");
        this.authenticated = Objects.requireNonNull(authenticated, "authenticated");
        this.warn = Objects.requireNonNull(warn, "warn");
        this.store = new HomeStore(folder.resolve("homes.yml"));
        this.store.load(warn);
        reloadSettings();
    }

    public void reloadSettings() {
        this.warmupSeconds = Math.max(0, plugin.getConfig().getInt("homes.warmup-seconds", 5));
        this.safety = TeleportSafety.of(plugin.getConfig().getStringList("teleport.blocked-worlds"));
    }

    public void reload() {
        cancelAll();
        store.load(warn);
        reloadSettings();
    }

    public int limit(final UUID id) {
        return RankPerks.of(ranks.rankOf(id)).homeSlots();
    }

    /** Returns the home teleport cooldown in seconds for the given player's current rank. */
    public int cooldownSeconds(final UUID id) {
        return RankPerks.of(ranks.rankOf(id)).homeCooldownSeconds();
    }

    /** Returns the remaining cooldown in milliseconds, or 0 if no cooldown is active. */
    public long cooldownRemaining(final UUID id) {
        final Long until = cooldowns.get(id);
        final long now = System.currentTimeMillis();
        if (until == null || until <= now) {
            cooldowns.remove(id);
            return 0L;
        }
        return until - now;
    }

    private void startCooldown(final UUID id, final int seconds) {
        if (seconds <= 0) {
            cooldowns.remove(id);
            return;
        }
        cooldowns.put(id, System.currentTimeMillis() + seconds * 1000L);
    }

    public Map<Integer, Home> homes(final UUID id) {
        return store.homes(id);
    }

    public boolean isBlocked(final World world) {
        return safety.isBlocked(world);
    }

    public void openMenu(final Player player) {
        if (!authenticated.test(player.getUniqueId())) {
            player.sendMessage(error("You must log in first."));
            return;
        }
        final HomeMenu menu = new HomeMenu(
                player.getUniqueId(), limit(player.getUniqueId()), store.homes(player.getUniqueId()));
        menu.open(player);
    }

    public void setHome(final Player player, final int slot) {
        final int limit = limit(player.getUniqueId());
        if (slot < 1 || slot > limit) {
            player.sendMessage(error("Home slot must be between 1 and " + limit + "."));
            return;
        }
        if (!authenticated.test(player.getUniqueId())) {
            player.sendMessage(error("You must log in first."));
            return;
        }
        if (safety.isBlocked(player.getWorld())) {
            player.sendMessage(error("Homes cannot be set in this world."));
            return;
        }

        final Location location = player.getLocation();
        final Home home = new Home(
                slot,
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
        final Optional<Home> previous = store.get(player.getUniqueId(), slot);
        store.put(player.getUniqueId(), home);
        if (!store.save(warn)) {
            previous.ifPresentOrElse(
                    old -> store.put(player.getUniqueId(), old),
                    () -> store.remove(player.getUniqueId(), slot));
            player.sendMessage(error("Could not save that home. Nothing was changed."));
            return;
        }
        player.sendMessage(Component.text("Home " + slot + " set.", NamedTextColor.GREEN));
    }

    /** Starts the warmup after a home icon was clicked. */
    public void select(final Player player, final int slot) {
        if (!authenticated.test(player.getUniqueId())) {
            player.sendMessage(error("You must log in first."));
            return;
        }
        if (slot < 1 || slot > limit(player.getUniqueId())) {
            player.sendMessage(error("That home slot is locked at your current rank."));
            return;
        }
        final Optional<Home> found = store.get(player.getUniqueId(), slot);
        if (found.isEmpty()) {
            player.sendMessage(error("That home slot is not set. Use /homeset " + slot + "."));
            return;
        }
        final World world = Bukkit.getWorld(found.get().world());
        if (world == null || safety.isBlocked(world)) {
            player.sendMessage(error("That home is in a world that is not available."));
            return;
        }
        final Location target = safety.safe(new Location(
                world,
                found.get().x(),
                found.get().y(),
                found.get().z(),
                found.get().yaw(),
                found.get().pitch()));
        if (target == null) {
            player.sendMessage(error("That home is not a safe destination."));
            return;
        }

        cancel(player.getUniqueId(), null);
        final Location start = player.getLocation();
        final long now = System.currentTimeMillis();
        final Pending next = new Pending(
                slot,
                target,
                new TeleportWarmup(
                        start.getWorld() == null ? null : start.getWorld().getName(),
                        start.getX(),
                        start.getY(),
                        start.getZ(),
                        now,
                        warmupSeconds * 1000L),
                warmupSeconds);
        pending.put(player.getUniqueId(), next);
        player.sendMessage(Component.text(
                "Teleporting to home " + slot + " in " + warmupSeconds + "s. Do not move.",
                NamedTextColor.YELLOW));
        player.sendActionBar(actionBar(warmupSeconds));
        ensureTicker();
    }

    /** Cancels a pending warmup, optionally telling the player why. */
    public void cancel(final UUID id, final String reason) {
        final Pending removed = pending.remove(id);
        if (removed != null && reason != null) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage(error(reason));
            }
        }
        stopTickerIfIdle();
    }

    public void cancelAll() {
        pending.clear();
        stopTickerIfIdle();
    }

    @Override
    public void close() {
        cancelAll();
        store.save(warn);
    }

    // ------------------------------------------------------------------------------------------
    // Events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Pending active = pending.get(event.getPlayer().getUniqueId());
        if (active == null || event.getTo() == null) {
            return;
        }
        final Location to = event.getTo();
        if (active.warmup.moved(
                to.getWorld() == null ? null : to.getWorld().getName(),
                to.getX(),
                to.getY(),
                to.getZ())) {
            cancel(event.getPlayer().getUniqueId(), "Teleport cancelled because you moved.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        cancel(event.getEntity().getUniqueId(), "Teleport cancelled because you died.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(final PlayerChangedWorldEvent event) {
        cancel(event.getPlayer().getUniqueId(), "Teleport cancelled because you changed worlds.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (pending.containsKey(event.getPlayer().getUniqueId())) {
            cancel(event.getPlayer().getUniqueId(), "Teleport cancelled by another teleport.");
        }
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof final HomeMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof final Player player)
                || !menu.owner().equals(player.getUniqueId())) {
            return;
        }
        if (event.getRawSlot() == HomeMenu.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        final int slot = menu.homeSlot(event.getRawSlot());
        if (slot > 0) {
            player.closeInventory();
            select(player, slot);
        }
    }

    @EventHandler
    public void onDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof HomeMenu) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Warmup scheduler

    private void ensureTicker() {
        if (ticker != null) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, TICK_MILLIS / 50L, TICK_MILLIS / 50L);
    }

    private void stopTickerIfIdle() {
        if (pending.isEmpty() && ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void tick() {
        if (pending.isEmpty()) {
            stopTickerIfIdle();
            return;
        }

        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<UUID, Pending>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Pending> entry = iterator.next();
            final UUID id = entry.getKey();
            final Pending active = entry.getValue();
            final Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || !authenticated.test(id)) {
                iterator.remove();
                continue;
            }
            if (active.slot > limit(id)) {
                iterator.remove();
                player.sendMessage(error("Teleport cancelled because that home slot is now locked."));
                continue;
            }
            if (active.warmup.finished(now)) {
                iterator.remove();
                teleport(player, active);
                continue;
            }
            final int remaining = active.warmup.secondsRemaining(now);
            if (remaining != active.lastSeconds) {
                active.lastSeconds = remaining;
                player.sendActionBar(actionBar(remaining));
            }
        }
        stopTickerIfIdle();
    }

    private void teleport(final Player player, final Pending active) {
        final Location destination = safety.safe(active.target);
        if (destination == null) {
            player.sendMessage(error("That home is no longer a safe destination."));
            return;
        }
        if (!player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            player.sendMessage(error("Could not teleport you to that home."));
            return;
        }
        player.sendMessage(Component.text(
                "Welcome to home " + active.slot + ".", NamedTextColor.GREEN));
    }

    private static Component actionBar(final int seconds) {
        return Component.text("Teleporting in " + seconds + "s...", NamedTextColor.YELLOW);
    }

    private static Component error(final String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    private static final class Pending {

        private final int slot;
        private final Location target;
        private final TeleportWarmup warmup;
        private int lastSeconds;

        private Pending(
                final int slot,
                final Location target,
                final TeleportWarmup warmup,
                final int lastSeconds) {
            this.slot = slot;
            this.target = target;
            this.warmup = warmup;
            this.lastSeconds = lastSeconds;
        }
    }
}
