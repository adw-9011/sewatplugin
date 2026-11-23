package io.github.near.sweats.vanish;

import io.github.near.sweats.chat.OnlinePlayerNames;
import io.github.near.sweats.rank.RankService;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Session-only vanish. Only operators and the DEV rank may vanish. Ordinary viewers lose the
 * tablist entry, entity/nametag visibility, entity interactions and player-lookup visibility;
 * operators and DEV keep seeing the vanished player.
 */
public final class VanishService implements Listener, AutoCloseable {

    private static final String DEV_RANK = "dev";

    private final Plugin plugin;
    private final RankService ranks;
    private final Predicate<UUID> authenticated;
    private final OnlinePlayerNames onlineNames;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public VanishService(
            final Plugin plugin,
            final RankService ranks,
            final Predicate<UUID> authenticated,
            final OnlinePlayerNames onlineNames) {
        this.plugin = plugin;
        this.ranks = ranks;
        this.authenticated = authenticated;
        this.onlineNames = onlineNames;
    }

    public boolean isVanished(final UUID id) {
        return vanished.contains(id);
    }

    public boolean canVanish(final CommandSender sender) {
        return sender.isOp()
                || (sender instanceof final Player player && isDev(player.getUniqueId()));
    }

    public boolean canSeeVanished(final Player viewer) {
        return viewer.isOp() || isDev(viewer.getUniqueId());
    }

    public static boolean shouldHide(final boolean viewerCanSee, final boolean targetVanished) {
        return targetVanished && !viewerCanSee;
    }

    static boolean isStaff(final boolean op, final String rankKey) {
        return op || DEV_RANK.equals(rankKey);
    }

    private boolean isDev(final UUID id) {
        return DEV_RANK.equals(ranks.rankOf(id).key());
    }

    public void vanish(final Player player) {
        if (!canVanish(player) || !authenticated.test(player.getUniqueId())) {
            player.sendMessage(error("Only operators and DEV staff can vanish."));
            return;
        }
        if (!vanished.add(player.getUniqueId())) {
            player.sendMessage(Component.text("You are already vanished.", NamedTextColor.GRAY));
            return;
        }
        apply(player);
        onlineNames.refresh(this::isVanished);
        player.sendMessage(Component.text("You're vanished", NamedTextColor.GRAY));
    }

    public void unvanish(final Player player) {
        if (!canVanish(player)) {
            player.sendMessage(error("Only operators and DEV staff can vanish."));
            return;
        }
        if (!vanished.remove(player.getUniqueId())) {
            player.sendMessage(Component.text("You are not vanished.", NamedTextColor.GRAY));
            return;
        }
        showToEveryone(player);
        onlineNames.refresh(this::isVanished);
        player.sendMessage(Component.text("You're no longer vanished.", NamedTextColor.GRAY));
    }

    /** Called after authentication: re-hide vanished players from this viewer. */
    public void onAuthenticated(final Player player) {
        hideVanishedFrom(player);
        onlineNames.refresh(this::isVanished);
    }

    /** Called on logout: a vanished player stops being vanished for the new session. */
    public void onDeauthenticated(final Player player) {
        if (vanished.remove(player.getUniqueId())) {
            showToEveryone(player);
            onlineNames.refresh(this::isVanished);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        vanished.remove(player.getUniqueId());
        hideVanishedFrom(player);
        onlineNames.refresh(this::isVanished);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        vanished.remove(event.getPlayer().getUniqueId());
        onlineNames.refresh(this::isVanished);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(final PlayerChangedWorldEvent event) {
        // A world change recreates the client-side entity tracker. Reapply both directions so a
        // vanished player never flashes into view and an ordinary player never disappears.
        for (final UUID id : vanished) {
            final Player hidden = Bukkit.getPlayer(id);
            if (hidden != null && !hidden.equals(event.getPlayer())) {
                if (canSeeVanished(event.getPlayer())) {
                    event.getPlayer().showPlayer(plugin, hidden);
                } else {
                    event.getPlayer().hidePlayer(plugin, hidden);
                }
            }
        }
        hideVanishedFrom(event.getPlayer());
        if (vanished.contains(event.getPlayer().getUniqueId())) {
            apply(event.getPlayer());
        }
    }

    public void clearState() {
        for (final UUID id : Set.copyOf(vanished)) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                showToEveryone(player);
            }
        }
        vanished.clear();
        onlineNames.refresh(this::isVanished);
    }

    @Override
    public void close() {
        clearState();
    }

    private void apply(final Player vanishedPlayer) {
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(vanishedPlayer)) {
                continue;
            }
            if (authenticated.test(viewer.getUniqueId()) && canSeeVanished(viewer)) {
                viewer.showPlayer(plugin, vanishedPlayer);
                vanishedPlayer.showPlayer(plugin, viewer);
            } else {
                viewer.hidePlayer(plugin, vanishedPlayer);
            }
        }
    }

    private void hideVanishedFrom(final Player viewer) {
        for (final UUID id : vanished) {
            final Player hidden = Bukkit.getPlayer(id);
            if (hidden == null || hidden.equals(viewer)) {
                continue;
            }
            if (authenticated.test(viewer.getUniqueId()) && canSeeVanished(viewer)) {
                viewer.showPlayer(plugin, hidden);
            } else {
                viewer.hidePlayer(plugin, hidden);
            }
        }
    }

    private void showToEveryone(final Player player) {
        for (final Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            if (!authenticated.test(other.getUniqueId())) {
                continue;
            }
            other.showPlayer(plugin, player);
            player.showPlayer(plugin, other);
        }
    }

    private static Component error(final String message) {
        return Component.text(message, NamedTextColor.RED);
    }
}
