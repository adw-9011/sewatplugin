package io.github.near.sweats.commands;

import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.permissions.Permission;

/** Grants and revokes permission nodes for a player or a rank. */
public final class GrantCommand implements CommandExecutor, TabCompleter {

    private final PermissionService permissions;
    private final RankService ranks;

    public GrantCommand(final PermissionService permissions, final RankService ranks) {
        this.permissions = permissions;
        this.ranks = ranks;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.PERMISSION_MANAGE)) {
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        final String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "user" -> grantUser(sender, args);
            case "rank" -> grantRank(sender, args);
            case "revoke" -> revoke(sender, args);
            case "list" -> list(sender, args);
            default -> usage(sender, label);
        }
        return true;
    }

    private void grantUser(final CommandSender sender, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage(CommandSupport.error("Usage: /grant user <player> <permission>"));
            return;
        }
        if (!PermissionNodes.isValid(args[2])) {
            sender.sendMessage(CommandSupport.error("Invalid permission node: " + args[2]));
            return;
        }

        final Optional<PlayerLookup.Target> target = findUser(args[1]);
        if (target.isEmpty()) {
            sender.sendMessage(CommandSupport.error("Unknown player: " + args[1]));
            return;
        }
        if (isRuneAccessNode(args[2])
                && !eligibleRuneRank(ranks.rankOf(target.get().id()))) {
            sender.sendMessage(CommandSupport.error(
                    "sweats.runes may only be granted to CM or DEV staff."));
            return;
        }

        try {
            final boolean changed = permissions.grantUser(
                    target.get().id(), target.get().name(), args[2]);
            sender.sendMessage(changed
                    ? CommandSupport.success("Granted " + args[2] + " to " + target.get().name() + ".")
                    : CommandSupport.error(target.get().name() + " already has " + args[2] + "."));
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        } catch (final IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(
                    "Could not save the permission change: " + exception.getMessage()));
        }
    }

    private void grantRank(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(CommandSupport.error("Usage: /grant rank <rankname> <permission>"));
            return;
        }

        final String node = args[args.length - 1];
        final String rankName = CommandSupport.join(args, 1, args.length - 1);
        if (!PermissionNodes.isValid(node)) {
            sender.sendMessage(CommandSupport.error("Invalid permission node: " + node));
            return;
        }

        try {
            final Rank rank = ranks.find(rankName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown rank: " + rankName));
            if (isRuneAccessNode(node) && !eligibleRuneRank(rank)) {
                sender.sendMessage(CommandSupport.error(
                        "sweats.runes may only be granted to the CM or DEV ranks."));
                return;
            }
            final boolean changed = permissions.grantRank(rankName, node);
            sender.sendMessage(changed
                    ? CommandSupport.success(
                            "Granted " + node + " to rank " + rank.name() + ".")
                    : CommandSupport.error(
                            "Rank " + rank.name() + " already has " + node + "."));
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        } catch (final IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(
                    "Could not save the permission change: " + exception.getMessage()));
        }
    }

    private void revoke(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(CommandSupport.error(
                    "Usage: /grant revoke <user|rank> <target> [permission]"));
            return;
        }

        final String scope = args[1].toLowerCase(Locale.ROOT);
        if (scope.equals("user")) {
            if (args.length != 4) {
                sender.sendMessage(CommandSupport.error(
                        "Usage: /grant revoke user <player> <permission>"));
                return;
            }
            if (!PermissionNodes.isValid(args[3])) {
                sender.sendMessage(CommandSupport.error(
                        "Invalid permission node: " + args[3]));
                return;
            }
            final Optional<PlayerLookup.Target> target = findUser(args[2]);
            if (target.isEmpty()) {
                sender.sendMessage(CommandSupport.error("Unknown player: " + args[2]));
                return;
            }
            final boolean changed;
            try {
                changed = permissions.revokeUser(target.get().id(), args[3]);
            } catch (final IllegalStateException exception) {
                sender.sendMessage(CommandSupport.error(
                        "Could not save the permission change: " + exception.getMessage()));
                return;
            }
            sender.sendMessage(changed
                    ? CommandSupport.success(
                            "Revoked " + args[3] + " from " + target.get().name() + ".")
                    : CommandSupport.error(
                            target.get().name() + " does not have " + args[3] + "."));
            return;
        }
        if (scope.equals("rank")) {
            if (args.length < 4) {
                sender.sendMessage(CommandSupport.error(
                        "Usage: /grant revoke rank <rankname> <permission>"));
                return;
            }
            final String node = args[args.length - 1];
            if (!PermissionNodes.isValid(node)) {
                sender.sendMessage(CommandSupport.error("Invalid permission node: " + node));
                return;
            }
            final String rankName = CommandSupport.join(args, 2, args.length - 1);
            try {
                final Rank rank = ranks.find(rankName)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown rank: " + rankName));
                final boolean changed = permissions.revokeRank(rankName, node);
                sender.sendMessage(changed
                        ? CommandSupport.success(
                                "Revoked " + node + " from rank " + rank.name() + ".")
                        : CommandSupport.error(
                                "Rank " + rank.name() + " does not have " + node + "."));
            } catch (final IllegalArgumentException exception) {
                sender.sendMessage(CommandSupport.error(exception.getMessage()));
            } catch (final IllegalStateException exception) {
                sender.sendMessage(CommandSupport.error(
                        "Could not save the permission change: " + exception.getMessage()));
            }
            return;
        }
        usage(sender, "grant");
    }

    private void list(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(CommandSupport.error("Usage: /grant list <user|rank> <target>"));
            return;
        }

        final String scope = args[1].toLowerCase(Locale.ROOT);
        if (scope.equals("user")) {
            final Optional<PlayerLookup.Target> target = findUser(args[2]);
            if (target.isEmpty()) {
                sender.sendMessage(CommandSupport.error("Unknown player: " + args[2]));
                return;
            }
            final List<String> nodes = permissions.userPermissions(target.get().id());
            sender.sendMessage(CommandSupport.success(
                    "Permissions for " + target.get().name() + ":"));
            if (nodes.isEmpty()) {
                sender.sendMessage(Component.text("None"));
                return;
            }
            for (final String node : nodes) {
                sender.sendMessage(Component.text(node));
            }
            return;
        }
        if (scope.equals("rank")) {
            final String rankName = CommandSupport.join(args, 2);
            final Optional<Rank> found = ranks.find(rankName);
            if (found.isEmpty()) {
                sender.sendMessage(CommandSupport.error("Unknown rank: " + rankName));
                return;
            }
            final List<String> nodes = permissions.rankPermissions(found.get());
            sender.sendMessage(CommandSupport.success(
                    "Permissions for rank " + found.get().name() + ":"));
            if (nodes.isEmpty()) {
                sender.sendMessage(Component.text("None"));
                return;
            }
            for (final String node : nodes) {
                sender.sendMessage(Component.text(node));
            }
            return;
        }
        usage(sender, "grant");
    }

    private Optional<PlayerLookup.Target> findUser(final String name) {
        final Optional<PlayerLookup.Target> online = PlayerLookup.find(name);
        if (online.isPresent()) {
            return online;
        }
        return permissions.knownUser(name)
                .map(user -> new PlayerLookup.Target(user.id(), user.name(), null));
    }

    private static boolean isRuneAccessNode(final String node) {
        return PermissionNodes.RUNES.equalsIgnoreCase(node);
    }

    /** Sweat/Investor/Custom ranks are never eligible for the free rune browser. */
    private static boolean eligibleRuneRank(final Rank rank) {
        return "cm".equals(rank.key()) || "dev".equals(rank.key());
    }

    private static void usage(final CommandSender sender, final String label) {
        sender.sendMessage(Component.text("Permission commands:"));
        sender.sendMessage(Component.text("/" + label + " user <player> <permission>"));
        sender.sendMessage(Component.text("/" + label + " rank <rankname> <permission>"));
        sender.sendMessage(Component.text("/" + label + " revoke user <player> <permission>"));
        sender.sendMessage(Component.text("/" + label + " revoke rank <rankname> <permission>"));
        sender.sendMessage(Component.text("/" + label + " list user <player>"));
        sender.sendMessage(Component.text("/" + label + " list rank <rankname>"));
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.PERMISSION_MANAGE)) {
            return List.of();
        }
        if (args.length == 1) {
            return CommandSupport.matching(
                    List.of("user", "rank", "revoke", "list"), args[0]);
        }

        final String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (action) {
                case "user" -> CommandSupport.onlineNames(args[1]);
                case "rank" -> rankSuggestions(args[1]);
                case "revoke", "list" -> CommandSupport.matching(
                        List.of("user", "rank"), args[1]);
                default -> List.of();
            };
        }

        return switch (action) {
            case "user" -> args.length == 3
                    ? permissionSuggestions(args[2])
                    : List.of();
            case "rank" -> rankNodeSuggestions(args);
            case "revoke" -> revokeSuggestions(args);
            case "list" -> listSuggestions(args);
            default -> List.of();
        };
    }

    private List<String> rankNodeSuggestions(final String[] args) {
        if (args.length < 3) {
            return List.of();
        }
        final String rankName = CommandSupport.join(args, 1, args.length - 1);
        return ranks.find(rankName).isPresent()
                ? permissionSuggestions(args[args.length - 1])
                : List.of();
    }

    private List<String> revokeSuggestions(final String[] args) {
        final String scope = args[1].toLowerCase(Locale.ROOT);
        if (scope.equals("user")) {
            if (args.length == 3) {
                return CommandSupport.onlineNames(args[2]);
            }
            if (args.length == 4) {
                return permissionSuggestions(args[3]);
            }
            return List.of();
        }
        if (scope.equals("rank")) {
            if (args.length == 3) {
                return rankSuggestions(args[2]);
            }
            if (args.length >= 4) {
                final String rankName = CommandSupport.join(args, 2, args.length - 1);
                if (ranks.find(rankName).isPresent()) {
                    return permissionSuggestions(args[args.length - 1]);
                }
            }
        }
        return List.of();
    }

    private List<String> listSuggestions(final String[] args) {
        final String scope = args[1].toLowerCase(Locale.ROOT);
        if (scope.equals("user")) {
            return args.length == 3 ? CommandSupport.onlineNames(args[2]) : List.of();
        }
        if (scope.equals("rank")) {
            return args.length >= 3 ? rankSuggestions(CommandSupport.join(args, 2)) : List.of();
        }
        return List.of();
    }

    /**
     * Every permission the server knows about, plus this plugin's own nodes.
     *
     * <p>Bukkit registers the {@code plugin.yml} permissions at startup, so this also exposes
     * nodes from other plugins for tab completion.
     */
    private static List<String> permissionSuggestions(final String prefix) {
        final Set<String> nodes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        nodes.addAll(PermissionNodes.ALL);
        try {
            for (final Permission permission : Bukkit.getPluginManager().getPermissions()) {
                if (permission != null && permission.getName() != null) {
                    nodes.add(permission.getName());
                }
            }
        } catch (final RuntimeException ignored) {
            // No live server (unit tests, early startup): keep the built-in list.
        }
        return CommandSupport.matching(new ArrayList<>(nodes), prefix);
    }

    private List<String> rankSuggestions(final String prefix) {
        return CommandSupport.matching(
                ranks.ranks().stream().map(rank -> rank.name().replace(' ', '_')).toList(),
                prefix);
    }
}
