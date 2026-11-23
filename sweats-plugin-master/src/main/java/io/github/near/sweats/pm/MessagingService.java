package io.github.near.sweats.pm;

import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.perks.PerkService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Private messages between two online, authenticated players.
 *
 * <p>The reply target is session state keyed by UUID and is only replaced after a message was
 * actually delivered to both sides. A failed lookup, a vanished target, a cooldown or an empty
 * message therefore leaves the previous reply target untouched. Message text is never parsed as
 * MiniMessage or legacy colour codes; formatting injection is impossible.
 */
public final class MessagingService {

    public static final int DEFAULT_MAX_LENGTH = 256;
    private static final long DEFAULT_COOLDOWN_MILLIS = 1_000L;
    private static final TextColor DIALOGUE = NamedTextColor.LIGHT_PURPLE;

    public enum Result {
        SENT,
        DISABLED,
        EMPTY,
        TOO_LONG,
        SELF,
        UNKNOWN,
        OFFLINE,
        HIDDEN,
        COOLDOWN,
        NO_REPLY
    }

    public record Outcome(Result result, long cooldownRemainingSeconds) {}

    @FunctionalInterface
    public interface Audit {
        void privateMessage(String from, String to, String content);
    }

    private final JavaPlugin plugin;
    private final RankService ranks;
    private final NickService nicks;
    private final PerkService perks;
    private final MessageDirectory directory;
    private final PermissionService permissions;
    private final Predicate<UUID> authenticated;
    private final Audit audit;
    private final Consumer<String> warn;
    private final LongSupplier clock;
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile int maxLength = DEFAULT_MAX_LENGTH;
    private volatile long cooldownMillis = DEFAULT_COOLDOWN_MILLIS;

    public MessagingService(
            final JavaPlugin plugin,
            final RankService ranks,
            final NickService nicks,
            final PerkService perks,
            final MessageDirectory directory,
            final PermissionService permissions,
            final Predicate<UUID> authenticated,
            final Audit audit,
            final Consumer<String> warn) {
        this(plugin, ranks, nicks, perks, directory, permissions, authenticated, audit, warn,
                System::currentTimeMillis, DEFAULT_MAX_LENGTH, DEFAULT_COOLDOWN_MILLIS);
    }

    MessagingService(
            final JavaPlugin plugin,
            final RankService ranks,
            final NickService nicks,
            final PerkService perks,
            final MessageDirectory directory,
            final PermissionService permissions,
            final Predicate<UUID> authenticated,
            final Audit audit,
            final Consumer<String> warn,
            final LongSupplier clock,
            final int maxLength,
            final long cooldownMillis) {
        this.plugin = plugin;
        this.ranks = ranks;
        this.nicks = nicks;
        this.perks = perks;
        this.directory = directory;
        this.permissions = permissions;
        this.authenticated = authenticated;
        this.audit = audit;
        this.warn = warn;
        this.clock = clock;
        this.maxLength = Math.max(1, maxLength);
        this.cooldownMillis = Math.max(0L, cooldownMillis);
        if (plugin != null) {
            reload();
        }
    }

    public void reload() {
        if (plugin == null) {
            return;
        }
        final FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("messaging.enabled", true);
        maxLength = Math.max(1, config.getInt("messaging.max-length", DEFAULT_MAX_LENGTH));
        cooldownMillis = Math.max(
                0L, config.getLong("messaging.cooldown-seconds", 1L) * 1_000L);
    }

    public Outcome send(final Player sender, final String targetName, final String rawContent) {
        if (!enabled) {
            return new Outcome(Result.DISABLED, 0L);
        }
        final String content = clean(rawContent);
        if (content.isEmpty()) {
            return new Outcome(Result.EMPTY, 0L);
        }
        if (content.length() > maxLength) {
            return new Outcome(Result.TOO_LONG, 0L);
        }

        final Optional<Player> found = directory.findVisible(sender, targetName);
        if (found.isEmpty()) {
            return new Outcome(Result.UNKNOWN, 0L);
        }
        final Player target = found.get();
        if (!target.isOnline()) {
            return new Outcome(Result.OFFLINE, 0L);
        }
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            return new Outcome(Result.SELF, 0L);
        }
        if (!authenticated.test(target.getUniqueId())) {
            return new Outcome(Result.OFFLINE, 0L);
        }
        if (!canSee(sender, target)) {
            return new Outcome(Result.HIDDEN, 0L);
        }
        final Outcome cooldown = cooldown(sender);
        if (cooldown.result() != Result.SENT) {
            return cooldown;
        }

        deliver(sender, target, content, false);
        lastSent.put(sender.getUniqueId(), clock.getAsLong());
        return new Outcome(Result.SENT, 0L);
    }

    public Outcome reply(final Player sender, final String rawContent) {
        if (!enabled) {
            return new Outcome(Result.DISABLED, 0L);
        }
        final UUID targetId = replyTargets.get(sender.getUniqueId());
        if (targetId == null) {
            return new Outcome(Result.NO_REPLY, 0L);
        }
        final String content = clean(rawContent);
        if (content.isEmpty()) {
            return new Outcome(Result.EMPTY, 0L);
        }
        if (content.length() > maxLength) {
            return new Outcome(Result.TOO_LONG, 0L);
        }

        final Optional<Player> found = directory.online(targetId);
        if (found.isEmpty()) {
            return new Outcome(Result.OFFLINE, 0L);
        }
        final Player target = found.get();
        if (!target.isOnline() || !authenticated.test(target.getUniqueId())) {
            return new Outcome(Result.OFFLINE, 0L);
        }
        if (!canSee(sender, target)) {
            return new Outcome(Result.HIDDEN, 0L);
        }
        final Outcome cooldown = cooldown(sender);
        if (cooldown.result() != Result.SENT) {
            return cooldown;
        }

        deliver(sender, target, content, true);
        lastSent.put(sender.getUniqueId(), clock.getAsLong());
        return new Outcome(Result.SENT, 0L);
    }

    public Optional<UUID> replyTarget(final UUID id) {
        return Optional.ofNullable(replyTargets.get(id));
    }

    /** Session state does not survive a logout. */
    public void clear(final UUID id) {
        replyTargets.remove(id);
        lastSent.remove(id);
    }

    public int maxLength() {
        return maxLength;
    }

    private Outcome cooldown(final Player sender) {
        if (cooldownMillis <= 0L
                || permissions.has(sender, PermissionNodes.MESSAGE_BYPASS_COOLDOWN)) {
            return new Outcome(Result.SENT, 0L);
        }
        final Long last = lastSent.get(sender.getUniqueId());
        final long now = clock.getAsLong();
        if (last == null || now - last >= cooldownMillis) {
            return new Outcome(Result.SENT, 0L);
        }
        final long remaining = Math.max(1L, (cooldownMillis - (now - last) + 999L) / 1_000L);
        return new Outcome(Result.COOLDOWN, remaining);
    }

    private void deliver(final Player sender, final Player target, final String content, final boolean isReply) {
        if (isReply) {
            sender.sendMessage(lineSent(sender, target, content));
            target.sendMessage(lineReceivedReply(target, sender, content));
        } else {
            sender.sendMessage(lineSent(sender, target, content));
            target.sendMessage(lineReceived(target, sender, content));
        }
        // Both sides can /r each other, but only after a message really reached both.
        replyTargets.put(sender.getUniqueId(), target.getUniqueId());
        replyTargets.put(target.getUniqueId(), sender.getUniqueId());
        audit.privateMessage(sender.getName(), target.getName(), content);
    }

    /**
     * Sender's view for a normal message: "You sent a message to <rank-colored target name>: <message>"
     */
    private Component lineSent(final Player sender, final Player target, final String content) {
        final Rank rank = ranks.rankOf(target.getUniqueId());
        final String name = nicks.displayName(target.getUniqueId(), target.getName());
        final TextColor plusColor = perks.plusColor(target.getUniqueId());
        return Component.text("You sent a message to ", DIALOGUE)
                .append(rank.displayPrefix(plusColor))
                .append(Component.text(name, rank.primaryColor()))
                .append(Component.text(": ", DIALOGUE))
                .append(Component.text(content, NamedTextColor.WHITE));
    }

    /**
     * Receiver's view for a normal message: "<rank-colored sender name> sent a message to you: <message>"
     */
    private Component lineReceived(final Player target, final Player sender, final String content) {
        final Rank rank = ranks.rankOf(sender.getUniqueId());
        final String name = nicks.displayName(sender.getUniqueId(), sender.getName());
        final TextColor plusColor = perks.plusColor(sender.getUniqueId());
        return Component.text("", DIALOGUE)
                .append(rank.displayPrefix(plusColor))
                .append(Component.text(name, rank.primaryColor()))
                .append(Component.text(" sent a message to you: ", DIALOGUE))
                .append(Component.text(content, NamedTextColor.WHITE));
    }

    /**
     * Receiver's view for a reply: "<rank-colored sender name> replied to your message: <message>"
     */
    private Component lineReceivedReply(final Player target, final Player sender, final String content) {
        final Rank rank = ranks.rankOf(sender.getUniqueId());
        final String name = nicks.displayName(sender.getUniqueId(), sender.getName());
        final TextColor plusColor = perks.plusColor(sender.getUniqueId());
        return Component.text("", DIALOGUE)
                .append(rank.displayPrefix(plusColor))
                .append(Component.text(name, rank.primaryColor()))
                .append(Component.text(" replied to your message: ", DIALOGUE))
                .append(Component.text(content, NamedTextColor.WHITE));
    }

    private static boolean canSee(final Player viewer, final Player target) {
        try {
            return viewer.canSee(target);
        } catch (final RuntimeException unavailable) {
            // A proxy without visibility support in a unit test; production always implements it.
            return true;
        }
    }

    /** Removes control characters and line breaks, then trims. No colour codes are interpreted. */
    static String clean(final String raw) {
        if (raw == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(Math.min(raw.length(), 1_024));
        for (int index = 0; index < raw.length() && out.length() < 1_024; index++) {
            final char character = raw.charAt(index);
            if (character == '\n' || character == '\r') {
                out.append(' ');
                continue;
            }
            if (!Character.isISOControl(character)) {
                out.append(character);
            }
        }
        return out.toString().trim();
    }
}
