package io.github.near.sweats.commands;

import io.github.near.sweats.friends.FriendsService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Friend system commands: /friend and /f.
 */
public final class FriendCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "add", "accept", "deny", "remove", "list", "requests", "best", "nickname",
            "notifications", "toggle");

    private final FriendsService friends;
    private final PermissionService permissions;

    public FriendCommand(final FriendsService friends, final PermissionService permissions) {
        this.friends = friends;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.FRIEND)) {
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (!friends.isReady()) {
            player.sendMessage(Component.text(
                    "The friends database is unavailable.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            usage(player, label);
            return true;
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("add")) {
            return add(player, label, shift(args));
        }
        if (!SUBCOMMANDS.contains(sub) && args.length == 1) {
            return add(player, label, args);
        }

        return switch (sub) {
            case "accept" -> accept(player, label, shift(args));
            case "deny" -> deny(player, label, shift(args));
            case "remove" -> remove(player, label, shift(args));
            case "list" -> list(player, label, shift(args));
            case "requests" -> requests(player);
            case "best" -> best(player, label, shift(args));
            case "nickname" -> nickname(player, label, shift(args));
            case "notifications" -> dispatch(player, friends.toggleNotifications(player));
            case "toggle" -> dispatch(player, friends.toggleRequests(player));
            default -> {
                player.sendMessage(Component.text(
                        "Unknown subcommand: " + args[0] + ".", NamedTextColor.RED));
                usage(player, label);
                yield true;
            }
        };
    }

    private boolean add(final Player player, final String label, final String[] args) {
        if (args.length != 1) {
            player.sendMessage(Component.text(
                    "Usage: /" + label + " add <player>", NamedTextColor.RED));
            return true;
        }
        final PlayerLookup.Target target = PlayerLookup.find(args[0], player).orElse(null);
        if (target == null || target.online() == null || !target.online().isOnline()) {
            player.sendMessage(Component.text(
                    "Player not found or offline.", NamedTextColor.RED));
            return true;
        }
        return dispatch(player, friends.sendRequest(player, target.online()));
    }

    private boolean accept(final Player player, final String label, final String[] args) {
        if (args.length != 1) {
            player.sendMessage(Component.text(
                    "Usage: /" + label + " accept <player>", NamedTextColor.RED));
            return true;
        }
        return dispatch(player, friends.acceptRequest(player, args[0]));
    }

    private boolean deny(final Player player, final String label, final String[] args) {
        if (args.length != 1) {
            player.sendMessage(Component.text(
                    "Usage: /" + label + " deny <player>", NamedTextColor.RED));
            return true;
        }
        return dispatch(player, friends.denyRequest(player, args[0]));
    }

    private boolean remove(final Player player, final String label, final String[] args) {
        if (args.length != 1) {
            player.sendMessage(Component.text(
                    "Usage: /" + label + " remove <player>", NamedTextColor.RED));
            return true;
        }
        return dispatch(player, friends.removeFriend(player, args[0]));
    }

    private boolean list(final Player player, final String label, final String[] args) {
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (final NumberFormatException ignored) {
                player.sendMessage(Component.text("Invalid page number.", NamedTextColor.RED));
                return true;
            }
        }
        return dispatch(player, friends.openFriendList(player, page));
    }

    private boolean requests(final Player player) {
        return dispatch(player, friends.listIncomingRequests(player));
    }

    private boolean best(final Player player, final String label, final String[] args) {
        if (args.length != 1) {
            player.sendMessage(Component.text(
                    "Usage: /" + label + " best <player>", NamedTextColor.RED));
            return true;
        }
        return dispatch(player, friends.toggleBestFriend(player, args[0]));
    }

    private boolean nickname(final Player player, final String label, final String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text(
                    "Usage: /" + label + " nickname <player> <nickname|reset>",
                    NamedTextColor.RED));
            return true;
        }
        final String targetName = args[0];
        final String nickname = CommandSupport.join(args, 1);
        return dispatch(player, friends.setNickname(player, targetName, nickname));
    }

    private boolean dispatch(final Player player, final CompletableFuture<FriendsService.Result> future) {
        future.whenComplete((result, error) -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null || result == null) {
                player.sendMessage(CommandSupport.error("Could not complete the friend request."));
                return;
            }
            result.send(player);
        });
        return true;
    }

    private void usage(final Player player, final String label) {
        player.sendMessage(Component.text("Friend commands:", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("  /" + label + " add <player> - Send a friend request"));
        player.sendMessage(Component.text("  /" + label + " accept <player> - Accept a friend request"));
        player.sendMessage(Component.text("  /" + label + " deny <player> - Deny a friend request"));
        player.sendMessage(Component.text("  /" + label + " remove <player> - Remove a friend"));
        player.sendMessage(Component.text("  /" + label + " list [page] - List your friends"));
        player.sendMessage(Component.text("  /" + label + " requests - List pending incoming requests"));
        player.sendMessage(Component.text("  /" + label + " best <player> - Toggle best friend"));
        player.sendMessage(Component.text(
                "  /" + label + " nickname <player> <nick|reset> - Set personal nickname"));
        player.sendMessage(Component.text(
                "  /" + label + " notifications - Toggle join/leave notifications"));
        player.sendMessage(Component.text(
                "  /" + label + " toggle - Toggle incoming friend requests"));
        player.sendMessage(Component.text(
                "Shorthand: /" + label + " <player> sends a request.", NamedTextColor.GRAY));
    }

    private String[] shift(final String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        return java.util.Arrays.copyOfRange(args, 1, args.length);
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (args.length == 1) {
            return CommandSupport.matching(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            final String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("add") || sub.equals("accept") || sub.equals("deny")
                    || sub.equals("remove") || sub.equals("best") || sub.equals("nickname")) {
                return CommandSupport.onlineNames(args[1], sender);
            }
            if (sub.equals("list")) {
                return List.of("1");
            }
        }
        if (args.length == 3 && args[0].toLowerCase(Locale.ROOT).equals("nickname")) {
            return List.of("<nickname>", "reset");
        }
        return List.of();
    }
}
