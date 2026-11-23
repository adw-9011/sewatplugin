package io.github.near.sweats.commands;

import io.github.near.sweats.maintenance.MaintenanceService;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * {@code /maintenance on|off|add|remove|list}.
 *
 * <p>Turning it on swaps the server-list MOTD, kicks everyone who is not allowed to bypass, and
 * rejects every other connection until it is turned off.
 */
public final class MaintenanceCommand implements CommandExecutor, TabCompleter {

    private static final List<String> OPTIONS =
            List.of("on", "off", "add", "remove", "list");

    private final MaintenanceService maintenance;

    public MaintenanceCommand(final MaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "start", "enable" -> maintenance.enable(sender);
            case "off", "stop", "disable" -> maintenance.disable(sender);
            case "list", "status" -> maintenance.status(sender);
            case "add", "allow", "adduser" -> {
                if (args.length < 2) {
                    sender.sendMessage(CommandSupport.error(
                            "Usage: /" + label + " add <player>"));
                    return true;
                }
                final String name = resolveName(sender, args[1]);
                if (name != null) {
                    maintenance.addBypass(sender, name);
                }
            }
            case "remove", "deny", "removeuser" -> {
                if (args.length < 2) {
                    sender.sendMessage(CommandSupport.error(
                            "Usage: /" + label + " remove <player>"));
                    return true;
                }
                maintenance.removeBypass(sender, args[1]);
            }
            default -> usage(sender, label);
        }
        return true;
    }

    /** Resolves an online or previously seen player so a typo cannot silently join the list. */
    private static String resolveName(final CommandSender sender, final String name) {
        return PlayerLookup.find(name)
                .map(target -> target.name())
                .orElseGet(() -> {
                    sender.sendMessage(CommandSupport.error("Unknown player: " + name));
                    return null;
                });
    }

    private static void usage(final CommandSender sender, final String label) {
        sender.sendMessage(Component.text(
                "Usage: /" + label + " <on|off|add <player>|remove <player>|list>",
                NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (args.length == 1) {
            return CommandSupport.matching(OPTIONS, args[0]);
        }
        if (args.length != 2) {
            return List.of();
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "add", "allow", "adduser" -> CommandSupport.onlineNames(args[1]);
            case "remove", "deny", "removeuser" ->
                    CommandSupport.matching(maintenance.bypasses(), args[1]);
            default -> List.of();
        };
    }
}
