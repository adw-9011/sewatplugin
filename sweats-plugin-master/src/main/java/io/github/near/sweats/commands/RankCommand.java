package io.github.near.sweats.commands;

import io.github.near.sweats.display.DisplayTheme;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class RankCommand implements CommandExecutor, TabCompleter {

    private final RankService ranks;
    private final PermissionService permissions;

    public RankCommand(final RankService ranks, final PermissionService permissions) {
        this.ranks = ranks;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.RANK_MANAGE)) {
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "set" -> set(sender, args);
            case "info" -> info(sender, args);
            case "list" -> list(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    private void create(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(CommandSupport.error(
                    "Usage: /rank create <rankname> <weight>"));
            return;
        }

        final String name = CommandSupport.join(args, 1, args.length - 1);
        final int weight;
        try {
            weight = Integer.parseInt(args[args.length - 1]);
        } catch (final NumberFormatException exception) {
            sender.sendMessage(CommandSupport.error("Weight must be a whole number."));
            return;
        }

        try {
            final Rank rank = ranks.create(name, weight);
            sender.sendMessage(CommandSupport.success("Created rank " + rank.name()
                    + " with weight " + rank.weight() + "."));
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
    }

    private void delete(final CommandSender sender, final String[] args) {
        final String name = CommandSupport.join(args, 1);
        if (name.isBlank()) {
            sender.sendMessage(CommandSupport.error("Usage: /rank delete <rankname>"));
            return;
        }

        try {
            ranks.delete(name);
            sender.sendMessage(CommandSupport.success("Deleted rank " + name + "."));
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
    }

    private void set(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(CommandSupport.error("Usage: /rank set <player> <rankname>"));
            return;
        }

        final String playerName = args[1];
        final String rankName = CommandSupport.join(args, 2);
        Optional<PlayerLookup.Target> target = PlayerLookup.find(playerName);
        if (target.isEmpty()) {
            target = ranks.knownPlayer(playerName)
                    .map(known -> new PlayerLookup.Target(known.id(), known.name(), null));
        }
        if (target.isEmpty()) {
            sender.sendMessage(CommandSupport.error(
                    "Unknown player: " + playerName + ". They must have played on this server."));
            return;
        }

        try {
            final boolean changed = ranks.assign(
                    target.get().id(), target.get().name(), rankName);
            final Rank rank = ranks.find(rankName).orElseThrow();
            if (changed) {
                sender.sendMessage(CommandSupport.success(
                        "Set " + target.get().name() + "'s rank to " + rank.name() + "."));
            } else {
                sender.sendMessage(CommandSupport.error(
                        target.get().name() + " already has rank " + rank.name() + "."));
            }
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
    }

    private void info(final CommandSender sender, final String[] args) {
        final String name = CommandSupport.join(args, 1);
        if (name.isBlank()) {
            sender.sendMessage(CommandSupport.error("Usage: /rank info <rankname>"));
            return;
        }

        final Optional<Rank> found = ranks.find(name);
        if (found.isEmpty()) {
            sender.sendMessage(CommandSupport.error("Unknown rank: " + name));
            return;
        }

        final Rank rank = found.get();
        sender.sendMessage(rank.prefix()
                .append(Component.text("Rank information", DisplayTheme.PURPLE)));
        sender.sendMessage(Component.text("Weight: ", DisplayTheme.BLUE)
                .append(Component.text(rank.weight(), DisplayTheme.TEXT)));
        sender.sendMessage(Component.text("Type: ", DisplayTheme.BLUE)
                .append(Component.text(rank.core() ? "Core" : "Custom", DisplayTheme.TEXT)));
        sender.sendMessage(Component.text("Assigned players: ", DisplayTheme.BLUE)
                .append(Component.text(ranks.assignedCount(rank), DisplayTheme.TEXT)));

        final List<String> assigned = ranks.assignedPlayers(rank);
        if (!assigned.isEmpty()) {
            sender.sendMessage(Component.text("Players: ", DisplayTheme.BLUE)
                    .append(Component.text(String.join(", ", assigned), DisplayTheme.SECONDARY)));
        }
    }

    private void list(final CommandSender sender) {
        sender.sendMessage(Component.text("Ranks (highest first):", DisplayTheme.PURPLE));
        for (final Rank rank : ranks.ranks()) {
            sender.sendMessage(rank.prefix()
                    .append(Component.text("weight " + rank.weight(), DisplayTheme.SECONDARY))
                    .append(Component.text(rank.core() ? " (core)" : "", NamedTextColor.GRAY)));
        }
    }

    private static void usage(final CommandSender sender, final String label) {
        sender.sendMessage(Component.text("Rank commands:", DisplayTheme.PURPLE));
        sender.sendMessage(Component.text("/" + label + " create <rankname> <weight>", DisplayTheme.TEXT));
        sender.sendMessage(Component.text("  Rank names support legacy formatting codes: &0-9, &a-f, &k (obfuscated),", DisplayTheme.SECONDARY));
        sender.sendMessage(Component.text("  &l (bold), &m (strikethrough), &n (underline), &o (italic), &r (reset)", DisplayTheme.SECONDARY));
        sender.sendMessage(Component.text("  Example: /rank create &aGrinder 10", DisplayTheme.SECONDARY));
        sender.sendMessage(Component.text("/" + label + " delete <rankname>", DisplayTheme.TEXT));
        sender.sendMessage(Component.text("/" + label + " set <player> <rankname>", DisplayTheme.TEXT));
        sender.sendMessage(Component.text("/" + label + " info <rankname>", DisplayTheme.TEXT));
        sender.sendMessage(Component.text("/" + label + " list", DisplayTheme.TEXT));
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.RANK_MANAGE)) {
            return List.of();
        }
        if (args.length == 1) {
            return CommandSupport.matching(
                    List.of("create", "delete", "set", "info", "list"), args[0]);
        }

        final String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "delete", "info" -> rankSuggestions(CommandSupport.join(args, 1));
            case "set" -> {
                if (args.length == 2) {
                    yield CommandSupport.onlineNames(args[1]);
                }
                yield rankSuggestions(CommandSupport.join(args, 2));
            }
            default -> List.of();
        };
    }

    private List<String> rankSuggestions(final String prefix) {
        final List<String> names = new ArrayList<>();
        for (final Rank rank : ranks.ranks()) {
            names.add(rank.name().replace(' ', '_'));
        }
        return CommandSupport.matching(names, prefix);
    }
}