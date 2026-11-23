package io.github.near.sweats.afk;

import io.github.near.sweats.SweatsPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tracks how long each player spends AFK.
 *
 * <p>A player becomes AFK after {@code afk.timeout-seconds} without movement, chat, commands or
 * interaction. The time is accumulated in whole seconds into {@code afk.yml} and exposed as ticks
 * for the AFK leaderboard. Nothing is kicked or teleported; this is tracking only.
 */
public final class AfkService implements Listener {

    private static final long SAVE_INTERVAL_MS = 60_000L;

    private final SweatsPlugin plugin;
    private final AfkStore store;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, Long> afkSince = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    private AfkSettings settings;
    private BukkitTask task;
    private long lastSave;

    public AfkService(final SweatsPlugin plugin) {
        this.plugin = plugin;
        this.store = new AfkStore(plugin.getDataFolder().toPath().resolve("afk.yml"));
        this.store.load(plugin.getLogger()::warning);
        this.settings = AfkSettings.from(plugin.getConfig());
        startTask();
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** Lifetime AFK time in ticks, including the current unfinished AFK session. */
    public long afkTicks(final UUID id) {
        if (id == null) {
            return 0L;
        }
        long seconds = store.afkSeconds(id);
        final Long since = afkSince.get(id);
        if (since != null) {
            seconds += Math.max(0L, (System.currentTimeMillis() - since) / 1000L);
        }
        return seconds * 20L;
    }

    public boolean isAfk(final UUID id) {
        return id != null && afkSince.containsKey(id);
    }

    public void reload() {
        closeAllSessions();
        this.settings = AfkSettings.from(plugin.getConfig());
        lastActivity.clear();
        final long now = System.currentTimeMillis();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            lastActivity.put(player.getUniqueId(), now);
            names.put(player.getUniqueId(), player.getName());
        }
        startTask();
    }

    /** Closes active sessions and flushes the file, used on shutdown. */
    public void clearState() {
        closeAllSessions();
        stopTask();
        store.save(plugin.getLogger()::warning);
    }

    public void saveIfDirty() {
        store.save(plugin.getLogger()::warning);
    }

    private void startTask() {
        stopTask();
        if (!settings.enabled()) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, settings.checkTicks(),
                settings.checkTicks());
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void check() {
        if (!settings.enabled()) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            final UUID id = player.getUniqueId();
            names.put(id, player.getName());
            final long last = lastActivity.computeIfAbsent(id, ignored -> now);
            if (now - last >= settings.timeoutMillis()) {
                afkSince.putIfAbsent(id, now);
            } else {
                closeSession(id, now);
            }
        }
        if (store.dirty() && now - lastSave >= SAVE_INTERVAL_MS) {
            lastSave = now;
            store.save(plugin.getLogger()::warning);
        }
    }

    private void touch(final UUID id, final String name) {
        if (!settings.enabled() || id == null) {
            return;
        }
        if (name != null && !name.isBlank()) {
            names.put(id, name);
        }
        lastActivity.put(id, System.currentTimeMillis());
        closeSession(id, System.currentTimeMillis());
    }

    private void closeSession(final UUID id, final long now) {
        final Long since = afkSince.remove(id);
        if (since != null) {
            store.addSeconds(id, names.getOrDefault(id, ""),
                    Math.max(0L, (now - since) / 1000L));
        }
    }

    private void closeAllSessions() {
        final long now = System.currentTimeMillis();
        for (final UUID id : new ArrayList<>(afkSince.keySet())) {
            closeSession(id, now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        names.put(player.getUniqueId(), player.getName());
        touch(player.getUniqueId(), player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        closeSession(id, System.currentTimeMillis());
        lastActivity.remove(id);
        names.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (event.getFrom().getWorld() != event.getTo().getWorld()
                || event.getFrom().distanceSquared(event.getTo()) > 0.0001D) {
            touch(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(final PlayerInteractEvent event) {
        touch(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        touch(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(final PlayerItemHeldEvent event) {
        touch(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
        touch(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    /** Paper's chat event may be fired off the main thread, so only concurrent maps are used. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(final AsyncChatEvent event) {
        final Player player = event.getPlayer();
        // getName() is a cheap immutable lookup, safe from the chat thread.
        touch(player.getUniqueId(), player.getName());
    }

    /** Short status text for operators. */
    public List<String> statusLines() {
        return List.of(
                "AFK tracking: " + (settings.enabled() ? "enabled" : "disabled"),
                "AFK timeout: " + settings.timeoutSeconds() + "s",
                "Currently AFK: " + afkSince.size());
    }
}
