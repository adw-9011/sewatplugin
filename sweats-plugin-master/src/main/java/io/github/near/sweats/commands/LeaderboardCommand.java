package io.github.near.sweats.commands;

import io.github.near.sweats.leaderboard.LeaderboardBoards;
import io.github.near.sweats.leaderboard.LeaderboardCategory;
import io.github.near.sweats.leaderboard.LeaderboardStore;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Places and manages the floating leaderboard displays.
 *
 * <p>{@code /leaderboard place [category]} puts a themed board above the sender, {@code remove}
 * deletes the nearest one, {@code list} prints every saved board and {@code categories} explains
 * the available themes. Boards are rendered per viewer: the top line shows that player's own score.
 */
public final class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private static final Set<String> SUBCOMMANDS = Set.of("place", "remove", "list", "categories");
    private static final List<String> SUGGESTIONS = List.of("categories", "list", "place", "remove");

    /** How far above the sender's feet a new board floats. The text hangs down from there. */
    private static final double PLACE_HEIGHT = 3.0D;

    /** How close the sender has to be to the board they want to remove. */
    private static final double REMOVE_RADIUS = 8.0D;

    private final LeaderboardBoards boards;
    private final PermissionService permissions;

    public LeaderboardCommand(final LeaderboardBoards boards, final PermissionService permissions) {
        this.boards = boards;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.LEADERBOARD)) {
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        final String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(subcommand)) {
            sender.sendMessage(CommandSupport.error("Unknown subcommand: " + args[0] + "."));
            usage(sender, label);
            return true;
        }

        switch (subcommand) {
            case "place" -> place(sender, label, args);
            case "remove" -> remove(sender, label);
            case "categories" -> categories(sender);
            default -> list(sender, label);
        }
        return true;
    }

    private void place(final CommandSender sender, final String label, final String[] args) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(CommandSupport.error(
                    "Only players can place a leaderboard. Run /" + label
                            + " list to see the existing ones."));
            return;
        }

        if (!boards.enabled()) {
            sender.sendMessage(CommandSupport.error(
                    "Leaderboards are disabled in config.yml, so nobody can see it yet."));
            return;
        }

        LeaderboardCategory category = LeaderboardCategory.PLAYTIME;
        if (args.length >= 2) {
            category = LeaderboardCategory.fromKey(args[1]);
            if (category == null) {
                sender.sendMessage(CommandSupport.error(
                        "Unknown category: " + args[1] + ". Use /" + label + " categories."));
                return;
            }
        }

        final LeaderboardStore.StoredDisplay stored;
        try {
            stored = boards.place(placement(player), category);
        } catch (final IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
            return;
        }
        sender.sendMessage(CommandSupport.success(
                "Placed a " + category.title() + " leaderboard at " + where(stored) + "."));
    }

    private void remove(final CommandSender sender, final String label) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(CommandSupport.error(
                    "Only players can remove a leaderboard. Use /" + label + " list first."));
            return;
        }
        final boolean removed;
        try {
            removed = boards.removeNearest(player.getLocation(), REMOVE_RADIUS);
        } catch (final IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
            return;
        }
        if (!removed) {
            sender.sendMessage(CommandSupport.error(
                    "No leaderboard within " + (int) REMOVE_RADIUS + " blocks of you."));
            return;
        }
        sender.sendMessage(CommandSupport.success("Removed the nearest leaderboard."));
    }

    private void list(final CommandSender sender, final String label) {
        final List<LeaderboardStore.StoredDisplay> displays = boards.displays();
        if (displays.isEmpty()) {
            sender.sendMessage(Component.text(
                    "There are no leaderboards yet. Use /" + label + " place."));
            return;
        }

        sender.sendMessage(Component.text("Leaderboards (" + displays.size() + "):"));
        for (final LeaderboardStore.StoredDisplay display : displays) {
            sender.sendMessage(Component.text(" - " + display.category().key()
                    + " " + where(display)));
        }
    }

    private void categories(final CommandSender sender) {
        sender.sendMessage(Component.text("Leaderboard categories:"));
        for (final LeaderboardCategory category : LeaderboardCategory.values()) {
            sender.sendMessage(Component.text(
                    " - " + category.key() + " - " + category.title()
                            + " (" + category.symbol() + ")"));
        }
    }

    private static Location placement(final Player player) {
        return player.getLocation().add(0.0D, PLACE_HEIGHT, 0.0D);
    }

    private static String where(final LeaderboardStore.StoredDisplay display) {
        return display.world()
                + " " + Math.round(display.x())
                + " " + Math.round(display.y())
                + " " + Math.round(display.z());
    }

    private static void usage(final CommandSender sender, final String label) {
        sender.sendMessage(Component.text("Leaderboard commands:"));
        sender.sendMessage(Component.text(
                "/" + label + " place [category] - place a themed board above you"));
        sender.sendMessage(Component.text(
                "/" + label + " remove - remove the nearest board"));
        sender.sendMessage(Component.text(
                "/" + label + " list - list every placed board"));
        sender.sendMessage(Component.text(
                "/" + label + " categories - show the available categories"));
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.LEADERBOARD)) {
            return List.of();
        }
        if (args.length == 1) {
            return CommandSupport.matching(SUGGESTIONS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            final List<String> keys = new ArrayList<>();
            for (final LeaderboardCategory category : LeaderboardCategory.values()) {
                keys.add(category.key());
            }
            return CommandSupport.matching(keys, args[1]);
        }
        return List.of();
    }
}
