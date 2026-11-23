package io.github.near.sweats.commands;

import io.github.near.sweats.SweatsPlugin;
import io.github.near.sweats.chat.OnlinePlayerNames;
import io.github.near.sweats.ping.PingSettings;
import io.github.near.sweats.ping.PingStatus;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class PingCommand implements CommandExecutor, TabCompleter {

    private static final String OTHERS_PERMISSION = "sweats.ping.others";

    private final OnlinePlayerNames onlineNames;
    private final Function<String, Player> playerLookup;
    private final Supplier<PingSettings> settingsSource;

    private volatile PingSettings settings;

    public PingCommand(final SweatsPlugin plugin, final OnlinePlayerNames onlineNames) {
        this(onlineNames, Bukkit::getPlayerExact, () -> PingSettings.from(plugin.getConfig()));
    }

    PingCommand(
            final OnlinePlayerNames onlineNames,
            final Function<String, Player> playerLookup,
            final Supplier<PingSettings> settingsSource) {
        this.onlineNames = onlineNames;
        this.playerLookup = playerLookup;
        this.settingsSource = settingsSource;
        this.settings = settingsSource.get();
    }

    public void reloadSettings() {
        this.settings = settingsSource.get();
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (args.length > 1) {
            sender.sendMessage(Component.text("Usage: /" + label + " [player]", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof final Player player)) {
                sender.sendMessage(Component.text(
                        "Console has no ping. Use /" + label + " <player>.", NamedTextColor.RED));
                return true;
            }
            sendPing(sender, player, "Your ping is: ");
            return true;
        }

        if (!sender.hasPermission(OTHERS_PERMISSION)) {
            sender.sendMessage(Component.text(
                    "You do not have permission to check other players' ping.", NamedTextColor.RED));
            return true;
        }

        final Player target = playerLookup.apply(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text(
                    "Player '" + args[0] + "' is not online.", NamedTextColor.RED));
            return true;
        }

        sendPing(sender, target, target.getName() + "'s ping is: ");
        return true;
    }

    private void sendPing(final CommandSender sender, final Player target, final String label) {
        final int ping = Math.max(0, target.getPing());
        final PingStatus status = PingStatus.forPing(ping, settings);
        sender.sendMessage(Component.text()
                .append(Component.text(label, NamedTextColor.GREEN))
                .append(Component.text(ping + " ms", status.color()))
                .build());
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (args.length != 1 || !sender.hasPermission(OTHERS_PERMISSION)) {
            return List.of();
        }

        final String prefix = args[0].toLowerCase(Locale.ROOT);
        return onlineNames.names().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}