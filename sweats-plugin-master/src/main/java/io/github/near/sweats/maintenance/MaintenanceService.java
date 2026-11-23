package io.github.near.sweats.maintenance;

import io.github.near.sweats.owner.Owner;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Maintenance mode: a server-list MOTD, a login gate and a bypass list.
 *
 * <p>The MOTD is changed with {@link org.bukkit.Server#motd(Component)}, which only updates the
 * in-memory value. Paper's string setter would also rewrite {@code server.properties}, so a crash
 * during maintenance could leave the maintenance MOTD installed forever. Keeping the original
 * component here means {@code /maintenance off} always restores it, and a restart while
 * maintenance is on still starts from the real MOTD in {@code server.properties}.
 */
public final class MaintenanceService implements Listener {

    public static final String ADMIN_PERMISSION = "sweats.maintenance";
    public static final String BYPASS_PERMISSION = "sweats.maintenance.bypass";

    private final Plugin plugin;
    private final MaintenanceStore store;
    private final Component originalMotd;
    /** Op/permission snapshot consulted by the asynchronous pre-login event. */
    private final Map<UUID, Boolean> accessSnapshot = new ConcurrentHashMap<>();

    private volatile MaintenanceSettings settings;

    public MaintenanceService(
            final Plugin plugin, final Path folder, final Consumer<String> warn) {
        this.plugin = plugin;
        this.settings = MaintenanceSettings.from(plugin.getConfig());
        this.store = new MaintenanceStore(folder.resolve("maintenance.yml"), warn);
        this.originalMotd = Bukkit.getServer().motd();

        // Seed ops once on the server thread. Permission plugins cannot be queried for offline
        // players safely, so staff who rely only on the bypass permission are refreshed when
        // they join (and can always be added to the explicit bypass list).
        for (final OfflinePlayer operator : Bukkit.getOperators()) {
            final UUID id = operator.getUniqueId();
            if (id != null) {
                accessSnapshot.put(id, true);
            }
        }

        if (store.state().enabled()) {
            applyMotd();
            plugin.getLogger().info(
                    "Maintenance mode is on; only bypassed players may join.");
        }
    }

    /** Re-reads the MOTD texts after {@code /sweats reload}, and re-applies them if needed. */
    public void reload() {
        this.settings = MaintenanceSettings.from(plugin.getConfig());
        if (store.state().enabled()) {
            applyMotd();
        }
    }

    /** Restores the real MOTD when the plugin is disabled mid-maintenance. */
    public void close() {
        if (store.state().enabled()) {
            Bukkit.getServer().motd(originalMotd);
        }
    }

    public boolean enabled() {
        return store.state().enabled();
    }

    public List<String> bypasses() {
        return store.state().bypass();
    }

    public void enable(final CommandSender sender) {
        switch (store.setEnabled(true)) {
            case UNCHANGED -> {
                sender.sendMessage(Component.text(
                        "Maintenance mode is already on.", NamedTextColor.YELLOW));
                return;
            }
            case SAVE_FAILED -> {
                sender.sendMessage(Component.text(
                        "Could not save maintenance.yml; maintenance mode was not changed.",
                        NamedTextColor.RED));
                return;
            }
            case CHANGED -> {
                // Continue below.
            }
        }

        applyMotd();
        final int kicked = kickPlayers();
        plugin.getLogger().info("Maintenance mode enabled by "
                + sender.getName() + "; kicked " + kicked + " player(s).");
        sender.sendMessage(Component.text(
                "Maintenance mode is on. Kicked " + kicked + " player(s).",
                NamedTextColor.GREEN));
    }

    public void disable(final CommandSender sender) {
        switch (store.setEnabled(false)) {
            case UNCHANGED -> {
                sender.sendMessage(Component.text(
                        "Maintenance mode is already off.", NamedTextColor.YELLOW));
                return;
            }
            case SAVE_FAILED -> {
                sender.sendMessage(Component.text(
                        "Could not save maintenance.yml; maintenance mode was not changed.",
                        NamedTextColor.RED));
                return;
            }
            case CHANGED -> {
                // Continue below.
            }
        }

        Bukkit.getServer().motd(originalMotd);
        plugin.getLogger().info("Maintenance mode disabled by " + sender.getName() + ".");
        sender.sendMessage(Component.text(
                "Maintenance mode is off. Players can join again.", NamedTextColor.GREEN));
    }

    public void addBypass(final CommandSender sender, final String name) {
        switch (store.addBypass(name)) {
            case UNCHANGED -> sender.sendMessage(Component.text(
                    name + " is already on the bypass list.", NamedTextColor.YELLOW));
            case SAVE_FAILED -> sender.sendMessage(Component.text(
                    "Could not save maintenance.yml; the bypass list was not changed.",
                    NamedTextColor.RED));
            case CHANGED -> sender.sendMessage(Component.text(
                    name + " can now join during maintenance.", NamedTextColor.GREEN));
        }
    }

    public void removeBypass(final CommandSender sender, final String name) {
        switch (store.removeBypass(name)) {
            case UNCHANGED -> sender.sendMessage(Component.text(
                    name + " is not on the bypass list.", NamedTextColor.YELLOW));
            case SAVE_FAILED -> sender.sendMessage(Component.text(
                    "Could not save maintenance.yml; the bypass list was not changed.",
                    NamedTextColor.RED));
            case CHANGED -> sender.sendMessage(Component.text(
                    name + " can no longer join during maintenance.", NamedTextColor.GREEN));
        }
    }

    public void status(final CommandSender sender) {
        final boolean on = store.state().enabled();
        sender.sendMessage(Component.text()
                .append(Component.text("Maintenance: ", NamedTextColor.GRAY))
                .append(Component.text(on ? "on" : "off",
                        on ? NamedTextColor.RED : NamedTextColor.GREEN))
                .build());

        final List<String> bypass = store.state().bypass();
        sender.sendMessage(Component.text(
                "Bypass list (" + bypass.size() + "): "
                        + (bypass.isEmpty() ? "nobody" : String.join(", ", bypass)),
                NamedTextColor.GRAY));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (!store.state().enabled()) {
            return;
        }
        if (canBypass(event.getName(), event.getUniqueId())) {
            return;
        }
        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER, settings.kickMessage());
    }

    /**
     * Rechecks on the main thread. Without this, a connection that read {@code enabled == false}
     * just before {@code /maintenance on} could still finish joining after the kick pass.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(final PlayerLoginEvent event) {
        if (!store.state().enabled() || canBypass(event.getPlayer())) {
            return;
        }
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, settings.kickMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        if (!store.state().enabled() || canBypass(event.getPlayer())) {
            return;
        }
        event.getPlayer().kick(settings.kickMessage(), PlayerKickEvent.Cause.PLUGIN);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        rememberAccess(event.getPlayer());
    }

    /** Permission check for a player who is already online (used before kicking them). */
    public boolean canBypass(final Player player) {
        rememberAccess(player);
        return Owner.isOwner(player.getUniqueId())
                || player.isOp()
                || player.hasPermission(BYPASS_PERMISSION)
                || store.state().hasBypass(player.getName());
    }

    /**
     * Thread-safe check for the asynchronous pre-login event.
     *
     * <p>No Bukkit lookup is allowed there: the player is not online yet, and
     * {@code getOfflinePlayer} may touch disk. The snapshot is refreshed on the main thread
     * whenever a player logs in, joins or quits; ops are seeded at startup.
     */
    private boolean canBypass(final String name, final UUID id) {
        if (Owner.isOwner(id)) {
            return true;
        }
        if (store.state().hasBypass(name)) {
            return true;
        }
        return id != null && Boolean.TRUE.equals(accessSnapshot.get(id));
    }

    private void rememberAccess(final Player player) {
        accessSnapshot.put(
                player.getUniqueId(),
                player.isOp() || player.hasPermission(BYPASS_PERMISSION));
    }

    private int kickPlayers() {
        int kicked = 0;
        for (final Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (canBypass(player)) {
                continue;
            }
            player.kick(settings.kickMessage(), PlayerKickEvent.Cause.PLUGIN);
            kicked++;
        }
        return kicked;
    }

    private void applyMotd() {
        Bukkit.getServer().motd(settings.motd());
    }
}
