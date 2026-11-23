package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public final class CommandSupport {

    private CommandSupport() {}

    private static final List<String> SELECTORS = List.of("@a", "@p", "@r");

    public static boolean isSelector(final String argument) {
        return argument != null && argument.startsWith("@");
    }

    /**
     * Turns a name or a vanilla selector (@a, @p, @r, @a[distance=..10], ...) into targets.
     *
     * <p>Plugin commands do not get selectors resolved for them, so this asks the server to do
     * it. Selectors only ever match online players. On failure the sender is told why and
     * {@code null} is returned.
     */
    public static List<PlayerLookup.Target> resolveTargets(
            final CommandSender sender, final String argument) {
        if (!isSelector(argument)) {
            final Optional<PlayerLookup.Target> found = PlayerLookup.find(argument, sender);
            if (found.isEmpty()) {
                sender.sendMessage(error("Unknown player: " + argument));
                return null;
            }
            return List.of(found.get());
        }

        final List<Entity> entities;
        try {
            entities = Bukkit.selectEntities(sender, argument);
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage(error("Invalid selector: " + argument));
            return null;
        }

        final List<PlayerLookup.Target> targets = new ArrayList<>();
        for (final Entity entity : entities) {
            if (entity instanceof final Player player) {
                targets.add(new PlayerLookup.Target(player.getUniqueId(), player.getName(), player));
            }
        }
        if (targets.isEmpty()) {
            sender.sendMessage(error("No players matched " + argument + "."));
            return null;
        }
        return targets;
    }

    /** Online names plus the selectors that make sense for a punishment. */
    public static List<String> playerSuggestions(final String prefix) {
        final List<String> suggestions = new ArrayList<>(onlineNames(prefix));
        suggestions.addAll(matching(SELECTORS, prefix));
        return suggestions;
    }

    public static String plural(final int count, final String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    public static boolean require(
            final CommandSender sender, final PermissionService permissions, final String node) {
        if (permissions.has(sender, node)) {
            return true;
        }
        sender.sendMessage(Component.text(
                "You do not have permission to use this command.", NamedTextColor.RED));
        return false;
    }

    /** Passes when the sender holds any one of the nodes. */
    public static boolean requireAny(
            final CommandSender sender,
            final PermissionService permissions,
            final String... nodes) {
        for (final String node : nodes) {
            if (permissions.has(sender, node)) {
                return true;
            }
        }
        sender.sendMessage(Component.text(
                "You do not have permission to use this command.", NamedTextColor.RED));
        return false;
    }

    /** Returns the sender as a logged-in player, or {@code null} after explaining why not. */
    public static Player authenticatedPlayer(final CommandSender sender, final AuthService auth) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(error("Only players can use this command."));
            return null;
        }
        if (!auth.isAuthenticated(player.getUniqueId())) {
            sender.sendMessage(error("You must log in first."));
            return null;
        }
        return player;
    }

    public static Component error(final String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    public static Component success(final String message) {
        return Component.text(message, NamedTextColor.GREEN);
    }

    public static String join(final String[] args, final int from) {
        return join(args, from, args.length);
    }

    public static String join(final String[] args, final int from, final int toExclusive) {
        final StringBuilder out = new StringBuilder();
        for (int index = from; index < toExclusive; index++) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(args[index]);
        }
        return out.toString().trim();
    }

    public static String punishmentDenial(
            final CommandSender sender, final UUID targetId, final RankService ranks) {
        if (!(sender instanceof final Player player)) {
            return null;
        }
        if (player.getUniqueId().equals(targetId)) {
            return "You cannot punish yourself.";
        }
        if (ranks.rankOf(player.getUniqueId()).weight() < ranks.rankOf(targetId).weight()) {
            return "You cannot punish a player with a higher rank.";
        }
        return null;
    }

    public static List<String> onlineNames(final String prefix) {
        return onlineNames(prefix, null);
    }

    public static List<String> onlineNames(final String prefix, final CommandSender viewer) {
        final String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        final List<String> names = new ArrayList<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (viewer instanceof final Player looking && !looking.canSee(player)) {
                continue;
            }
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(player.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static List<String> matching(final List<String> values, final String prefix) {
        final String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
