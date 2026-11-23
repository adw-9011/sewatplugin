package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /premium}: shows whether premium (Mojang) login is possible, or checks it now. */
public final class PremiumCommand implements CommandExecutor, TabCompleter {

    private static final List<String> OPTIONS = List.of("status", "check", "mute", "unmute");

    private final AuthService auth;

    public PremiumCommand(final AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text(
                    "Premium login is per player. Join the server to check it.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            auth.premiumStatus(player);
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text(
                    "Usage: /" + label + " [status|check|mute|unmute]", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> auth.premiumStatus(player);
            case "check" -> auth.checkPremium(player);
            case "mute", "off", "stop" -> auth.setPremiumPrompt(player, true);
            case "unmute", "on" -> auth.setPremiumPrompt(player, false);
            default -> player.sendMessage(Component.text(
                    "Usage: /" + label + " [status|check|mute|unmute]", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        return args.length == 1 ? CommandSupport.matching(OPTIONS, args[0]) : List.of();
    }
}
