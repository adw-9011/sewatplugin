package io.github.near.sweats.tpa;

import io.github.near.sweats.perks.RankPerks;
import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.teleport.TeleportSafety;
import io.github.near.sweats.tpa.TpaRegistry.Request;
import io.github.near.sweats.vanish.VanishService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Teleport requests. Requests are session-only, expire after a configurable time, and every
 * accept revalidates both players' login state, visibility and the destination.
 */
public final class TpaService implements Listener, AutoCloseable {

    private final Plugin plugin;
    private final RankService ranks;
    private final Predicate<UUID> authenticated;
    private final VanishService vanish;
    private final TpaRegistry registry = new TpaRegistry();

    private TeleportSafety safety;
    private int expireSeconds;

    public TpaService(
            final Plugin plugin,
            final RankService ranks,
            final Predicate<UUID> authenticated,
            final VanishService vanish) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ranks = Objects.requireNonNull(ranks, "ranks");
        this.authenticated = Objects.requireNonNull(authenticated, "authenticated");
        this.vanish = Objects.requireNonNull(vanish, "vanish");
        reloadSettings();
    }

    public void reloadSettings() {
        this.expireSeconds = Math.max(1, plugin.getConfig().getInt("tpa.expire-seconds", 60));
        this.safety = TeleportSafety.of(
                plugin.getConfig().getStringList("teleport.blocked-worlds"));
    }

    /** Returns the TPA cooldown in seconds for the given player's current rank. */
    public int cooldownSeconds(final UUID id) {
        return RankPerks.of(ranks.rankOf(id)).tpaCooldownSeconds();
    }

    public int expireSeconds() {
        return expireSeconds;
    }

    public Request request(
            final Player requester, final Player target, final boolean bypassCooldown) {
        if (requester.equals(target)) {
            throw new IllegalArgumentException("You cannot teleport to yourself.");
        }
        if (!authenticated.test(requester.getUniqueId())) {
            throw new IllegalArgumentException("You must log in first.");
        }
        if (!authenticated.test(target.getUniqueId())) {
            throw new IllegalArgumentException("That player is not logged in.");
        }
        if (!requester.canSee(target)) {
            throw new IllegalArgumentException("That player is not online.");
        }
        if (vanish.isVanished(requester.getUniqueId()) && !vanish.canSeeVanished(target)) {
            throw new IllegalArgumentException(
                    "You cannot send teleport requests to players who cannot see you.");
        }

        final long now = System.currentTimeMillis();
        final Request existing = registry.outgoing(requester.getUniqueId(), now).orElse(null);
        if (existing != null) {
            if (existing.target().equals(target.getUniqueId())) {
                throw new IllegalArgumentException(
                        "You already have a pending request to " + target.getName() + ".");
            }
            throw new IllegalArgumentException(
                    "You already have a pending teleport request. Use /tpcancel first.");
        }

        if (!bypassCooldown) {
            final long remaining = registry.cooldownRemaining(requester.getUniqueId(), now);
            if (remaining > 0L) {
                throw new IllegalArgumentException(
                        "Wait " + ((remaining + 999L) / 1000L) + "s before sending another request.");
            }
        }

        final Request request = registry.put(
                requester.getUniqueId(),
                target.getUniqueId(),
                now,
                expireSeconds * 1000L);
        if (!bypassCooldown) {
            registry.startCooldown(
                    requester.getUniqueId(), now, cooldownSeconds(requester.getUniqueId()) * 1000L);
        }

        target.sendMessage(Component.text()
                .append(Component.text(requester.getName(), NamedTextColor.AQUA))
                .append(Component.text(" wants to teleport to you. ", NamedTextColor.GRAY))
                .append(link("[ACCEPT]", NamedTextColor.GREEN, "/tpaccept " + requester.getName()))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(link("[DENY]", NamedTextColor.RED, "/tpdeny " + requester.getName()))
                .build());
        requester.sendMessage(Component.text(
                "Teleport request sent to " + target.getName()
                        + ". It expires in " + expireSeconds + "s.",
                NamedTextColor.GREEN));
        return request;
    }

    /** Accepts the newest request, or the named requester's request when a name is given. */
    public void accept(final Player target, final String requesterName) {
        if (!authenticated.test(target.getUniqueId())) {
            throw new IllegalArgumentException("You must log in first.");
        }
        final long now = System.currentTimeMillis();
        final Request request = choose(target, requesterName, now);
        registry.take(request.requester(), target.getUniqueId(), now);

        final Player requester = Bukkit.getPlayer(request.requester());
        if (requester == null || !requester.isOnline()
                || !authenticated.test(requester.getUniqueId())) {
            throw new IllegalArgumentException("That player is no longer online.");
        }
        if (!requester.canSee(target) || !target.canSee(requester)) {
            throw new IllegalArgumentException("That request is no longer available.");
        }
        if (vanish.isVanished(target.getUniqueId())
                && !vanish.canSeeVanished(requester)) {
            throw new IllegalArgumentException("That request is no longer available.");
        }

        final Location destination = safety.safe(target.getLocation());
        if (destination == null) {
            throw new IllegalArgumentException("That player is not standing somewhere safe.");
        }
        if (!requester.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            throw new IllegalStateException("Could not teleport to that player.");
        }

        requester.sendMessage(Component.text(
                "Teleported to " + target.getName() + ".", NamedTextColor.GREEN));
        target.sendMessage(Component.text(
                requester.getName() + " teleported to you.", NamedTextColor.GRAY));
    }

    public void deny(final Player target, final String requesterName) {
        if (!authenticated.test(target.getUniqueId())) {
            throw new IllegalArgumentException("You must log in first.");
        }
        final long now = System.currentTimeMillis();
        final Request request = choose(target, requesterName, now);
        registry.take(request.requester(), target.getUniqueId(), now);

        final Player requester = Bukkit.getPlayer(request.requester());
        if (requester != null && requester.isOnline()
                && authenticated.test(requester.getUniqueId())
                && requester.canSee(target)) {
            requester.sendMessage(Component.text(
                    target.getName() + " denied your teleport request.", NamedTextColor.RED));
        }
        target.sendMessage(Component.text(
                "You denied the teleport request.", NamedTextColor.GRAY));
    }

    public void cancel(final Player requester) {
        if (!authenticated.test(requester.getUniqueId())) {
            throw new IllegalArgumentException("You must log in first.");
        }
        final Request request = registry.cancel(requester.getUniqueId(), System.currentTimeMillis())
                .orElseThrow(() -> new IllegalArgumentException(
                        "You do not have a pending teleport request."));

        final Player target = Bukkit.getPlayer(request.target());
        if (target != null && target.isOnline()
                && authenticated.test(target.getUniqueId())
                && target.canSee(requester)) {
            target.sendMessage(Component.text(
                    requester.getName() + " cancelled their teleport request.", NamedTextColor.GRAY));
        }
        requester.sendMessage(Component.text(
                "Teleport request cancelled.", NamedTextColor.GRAY));
    }

    /** Online requester names with a pending request to {@code target}, for tab completion. */
    public List<String> incomingNames(final UUID target, final String prefix) {
        final String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        final List<String> names = new ArrayList<>();
        for (final Request request : registry.incoming(target, System.currentTimeMillis())) {
            final Player requester = Bukkit.getPlayer(request.requester());
            if (requester != null
                    && requester.isOnline()
                    && authenticated.test(requester.getUniqueId())
                    && requester.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(requester.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        registry.removePlayer(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        registry.clear();
    }

    private Request choose(final Player target, final String requesterName, final long now) {
        final List<Request> incoming = registry.incoming(target.getUniqueId(), now);
        if (incoming.isEmpty()) {
            throw new IllegalArgumentException(
                    "You do not have a pending teleport request.");
        }
        for (final Request request : incoming) {
            final Player requester = Bukkit.getPlayer(request.requester());
            if (requester == null || !requester.isOnline()
                    || !authenticated.test(requester.getUniqueId())) {
                registry.take(request.requester(), target.getUniqueId(), now);
                continue;
            }
            if (requesterName == null || requesterName.isBlank()
                    || requester.getName().equalsIgnoreCase(requesterName.trim())) {
                return request;
            }
        }
        if (requesterName != null && !requesterName.isBlank()) {
            throw new IllegalArgumentException(
                    "No pending request from " + requesterName + ".");
        }
        throw new IllegalArgumentException("You do not have a pending teleport request.");
    }

    private static Component link(
            final String label, final NamedTextColor color, final String command) {
        return Component.text(label, color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }
}
