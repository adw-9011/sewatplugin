package io.github.near.sweats.commands;

import io.github.near.sweats.SweatsPlugin;
import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.vote.VoteService;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** Small admin surface: {@code /sweats reload} and {@code /sweats auth ...}. */
public final class SweatsCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "sweats.auth.admin";
    private static final String VOTE_PERMISSION = "sweats.vote.admin";
    private static final List<String> AUTH_ACTIONS = List.of("status", "import", "unregister");
    private static final List<String> VOTE_ACTIONS = List.of("status", "test");

    private final SweatsPlugin plugin;
    private final ScoreboardCommand scoreboardCommand;

    public SweatsCommand(final SweatsPlugin plugin) {
        this.plugin = plugin;
        this.scoreboardCommand = new ScoreboardCommand(plugin);
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("scoreboard")) {
            return scoreboardCommand.execute(
                    sender, label, java.util.Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("auth")) {
            return auth(sender, label, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("vote")) {
            return vote(sender, label, args);
        }
        if (args.length != 1 || !args[0].toLowerCase(Locale.ROOT).equals("reload")) {
            sender.sendMessage(Component.text(
                    "Usage: /" + label + " <reload|auth|vote|scoreboard>", NamedTextColor.RED));
            return true;
        }
        if (!sender.hasPermission("sweats.admin")) {
            sender.sendMessage(Component.text("You do not have permission to reload Sweats.", NamedTextColor.RED));
            return true;
        }

        plugin.reload();
        sender.sendMessage(Component.text("Sweats config reloaded.", NamedTextColor.GREEN));
        return true;
    }

    private boolean auth(
            final CommandSender sender, final String label, final String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text(
                    "You do not have permission to manage authentication.", NamedTextColor.RED));
            return true;
        }

        final AuthService auth = plugin.authService();
        if (auth == null) {
            sender.sendMessage(Component.text("Auth is not available.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            for (final String line : auth.statusLines()) {
                sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
            }
            return true;
        }

        if (args.length == 2 && args[1].equalsIgnoreCase("import")) {
            auth.importLegacyNow(sender, imported -> {
                if (imported < 0) {
                    sender.sendMessage(Component.text(
                            "The auth database is unavailable or the import failed.",
                            NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text(
                            "Imported " + imported + " SimpleLogin account(s).",
                            NamedTextColor.GREEN));
                }
            });
            return true;
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("unregister")) {
            auth.unregister(sender, args[2], message ->
                    sender.sendMessage(Component.text(message, NamedTextColor.GRAY)));
            return true;
        }

        sender.sendMessage(Component.text(
                "Usage: /" + label + " auth <status|import|unregister <player>>",
                NamedTextColor.RED));
        return true;
    }

    private boolean vote(
            final CommandSender sender, final String label, final String[] args) {
        if (!sender.hasPermission(VOTE_PERMISSION)) {
            sender.sendMessage(Component.text(
                    "You do not have permission to manage votes.", NamedTextColor.RED));
            return true;
        }

        final VoteService votes = plugin.voteService();
        if (votes == null) {
            sender.sendMessage(Component.text("Votes are not available.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            for (final String line : votes.statusLines()) {
                sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
            }
            return true;
        }

        if (args.length >= 3 && args[1].equalsIgnoreCase("test")) {
            final String site = args.length >= 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : null;
            sender.sendMessage(Component.text(votes.simulate(args[2], site), NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text(
                "Usage: /" + label + " vote <status|test <player> [site]>",
                NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (args.length == 1) {
            return CommandSupport.matching(List.of("reload", "auth", "vote", "scoreboard"), args[0]);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("scoreboard")) {
            return scoreboardCommand.complete(
                    sender, java.util.Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("vote")) {
            return sender.hasPermission(VOTE_PERMISSION)
                    ? CommandSupport.matching(VOTE_ACTIONS, args[1]) : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("auth")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                return List.of();
            }
            return CommandSupport.matching(AUTH_ACTIONS, args[1]);
        }
        if (args.length == 3
                && args[0].equalsIgnoreCase("auth")
                && args[1].equalsIgnoreCase("unregister")
                && sender.hasPermission(ADMIN_PERMISSION)) {
            return CommandSupport.onlineNames(args[2]);
        }
        return List.of();
    }
}
