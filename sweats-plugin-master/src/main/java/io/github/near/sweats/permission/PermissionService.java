package io.github.near.sweats.permission;

import io.github.near.sweats.owner.Owner;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/**
 * Applies explicit user and rank permission grants to Bukkit players.
 *
 * <p>The YAML file is the source of truth. Online players receive a single permission attachment
 * rebuilt whenever their grants or rank change, so {@link CommandSender#hasPermission(String)} and
 * other plugins see the same result as {@link #has(UUID, String)}.
 */
public final class PermissionService implements Listener {

    private final Plugin plugin;
    private final RankService ranks;
    private final PermissionStore store;
    private final Consumer<String> warn;
    private final Set<UUID> online = new HashSet<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public PermissionService(
            final Plugin plugin,
            final Path folder,
            final RankService ranks,
            final Consumer<String> warn) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ranks = Objects.requireNonNull(ranks, "ranks");
        this.store = new PermissionStore(folder.resolve("permissions.yml"));
        this.warn = Objects.requireNonNull(warn, "warn");
        this.store.load(warn);
    }

    public boolean has(final CommandSender sender, final String node) {
        if (node == null) {
            return false;
        }
        if (!(sender instanceof final Player player)) {
            return sender.hasPermission(node);
        }
        if (has(player.getUniqueId(), node)) {
            return true;
        }
        // The Core registry is authoritative for this plugin's nodes: a permission left in a
        // stale Bukkit attachment must not survive a revoke. Defaults and operator grants still
        // count, so plugin.yml's default:op/default:true entries keep working.
        if (isManagedNode(node)) {
            return hasDefaultPermission(player, node);
        }
        return player.hasPermission(node);
    }

    private static boolean isManagedNode(final String node) {
        return node.toLowerCase(Locale.ROOT).startsWith("sweats.");
    }

    private static boolean hasDefaultPermission(final Player player, final String node) {
        final PermissionDefault fallback;
        try {
            final Permission permission = Bukkit.getPluginManager().getPermission(node);
            fallback = permission == null ? PermissionDefault.FALSE : permission.getDefault();
        } catch (final RuntimeException ignored) {
            // No live server (unit tests or early startup): fall back to the player's own
            // permission check so tests that mock hasPermission work correctly.
            return player.hasPermission(node);
        }
        return switch (fallback) {
            case TRUE -> true;
            case OP -> player.isOp();
            case NOT_OP -> !player.isOp();
            case FALSE -> false;
        };
    }

    public boolean has(final UUID id, final String node) {
        if (node == null || !PermissionNodes.isValid(node)) {
            return false;
        }
        if (Owner.isOwner(id)) {
            return true;
        }
        final String normalized = node.toLowerCase(Locale.ROOT);
        if (store.userPermissionSet(id).contains(normalized)) {
            return true;
        }
        final Rank rank = ranks.rankOf(id);
        return store.rankPermissionSet(rank.key()).contains(normalized)
                || PermissionNodes.derivedFor(rank).contains(normalized);
    }

    public List<String> userPermissions(final UUID id) {
        return store.userPermissions(id);
    }

    public List<String> rankPermissions(final Rank rank) {
        return store.rankPermissions(rank.key());
    }

    public Optional<PermissionUser> knownUser(final String name) {
        return store.knownUser(name);
    }

    public boolean grantUser(final UUID id, final String name, final String node) {
        final PermissionStore.UserState before = store.userState(id);
        final boolean changed = store.grantUser(id, name, node);
        if (!changed) {
            return false;
        }
        if (!store.save(warn)) {
            store.restoreUser(id, before);
            throw new IllegalStateException("Could not save permissions.yml");
        }
        refresh(id);
        return true;
    }

    public boolean revokeUser(final UUID id, final String node) {
        final PermissionStore.UserState before = store.userState(id);
        final boolean changed = store.revokeUser(id, node);
        if (!changed) {
            return false;
        }
        if (!store.save(warn)) {
            store.restoreUser(id, before);
            throw new IllegalStateException("Could not save permissions.yml");
        }
        refresh(id);
        return true;
    }

    public boolean grantRank(final String rankName, final String node) {
        final Rank rank = ranks.find(rankName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rank: " + rankName));
        final Set<String> before = store.rankState(rank.key());
        final boolean changed = store.grantRank(rank.key(), node);
        if (!changed) {
            return false;
        }
        if (!store.save(warn)) {
            store.restoreRank(rank.key(), before);
            throw new IllegalStateException("Could not save permissions.yml");
        }
        refreshRank(rank.key());
        return true;
    }

    public boolean revokeRank(final String rankName, final String node) {
        final Rank rank = ranks.find(rankName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rank: " + rankName));
        final Set<String> before = store.rankState(rank.key());
        final boolean changed = store.revokeRank(rank.key(), node);
        if (!changed) {
            return false;
        }
        if (!store.save(warn)) {
            store.restoreRank(rank.key(), before);
            throw new IllegalStateException("Could not save permissions.yml");
        }
        refreshRank(rank.key());
        return true;
    }

    public void rankChanged(final UUID id) {
        refresh(id);
    }

    public void rankListChanged() {
        final Set<String> valid = new HashSet<>();
        for (final Rank rank : ranks.ranks()) {
            valid.add(rank.key());
        }
        final Map<String, Set<String>> before = store.rankStates();
        if (store.retainRanks(valid)) {
            if (!store.save(warn)) {
                store.restoreRanks(before);
                return;
            }
            refreshTracked();
        }
    }

    public void applyAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            apply(player);
        }
    }

    public void reload() {
        store.load(warn);
        rankListChanged();
        refreshTracked();
    }

    public void clearState() {
        final Iterator<Map.Entry<UUID, PermissionAttachment>> iterator =
                attachments.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, PermissionAttachment> entry = iterator.next();
            final Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.removeAttachment(entry.getValue());
            }
            iterator.remove();
        }
        online.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID id = player.getUniqueId();
        online.add(id);
        final String storedName = store.userName(id);
        if (storedName != null && !storedName.equals(player.getName())) {
            final PermissionStore.UserState before = store.userState(id);
            store.touchUser(id, player.getName());
            if (!store.save(warn)) {
                store.restoreUser(id, before);
            }
        }
        apply(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        online.remove(id);
        attachments.remove(id);
    }

    private void refresh(final UUID id) {
        if (!online.contains(id)) {
            return;
        }
        final Player player = Bukkit.getPlayer(id);
        if (player == null) {
            online.remove(id);
            attachments.remove(id);
            return;
        }
        apply(player);
    }

    private void refreshRank(final String rankKey) {
        for (final UUID id : new ArrayList<>(online)) {
            if (ranks.rankOf(id).key().equals(rankKey)) {
                refresh(id);
            }
        }
    }

    private void refreshTracked() {
        for (final UUID id : new ArrayList<>(online)) {
            refresh(id);
        }
    }

    private void apply(final Player player) {
        final PermissionAttachment previous = attachments.remove(player.getUniqueId());
        if (previous != null) {
            player.removeAttachment(previous);
        }

        final boolean owner = Owner.isOwner(player.getUniqueId());
        final Rank rank = ranks.rankOf(player.getUniqueId());
        final Set<String> user = store.userPermissionSet(player.getUniqueId());
        final Set<String> rankNodes = store.rankPermissionSet(rank.key());
        final Set<String> derived = PermissionNodes.derivedFor(rank);
        if (user.isEmpty() && rankNodes.isEmpty() && derived.isEmpty() && !owner) {
            if (previous != null) {
                player.recalculatePermissions();
            }
            return;
        }

        final PermissionAttachment attachment = player.addAttachment(plugin);
        if (owner) {
            for (final String node : PermissionNodes.ALL) {
                attachment.setPermission(node, true);
            }
        }
        for (final String node : user) {
            attachment.setPermission(node, true);
        }
        for (final String node : rankNodes) {
            attachment.setPermission(node, true);
        }
        for (final String node : derived) {
            attachment.setPermission(node, true);
        }
        attachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();
    }
}
