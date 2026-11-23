package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /register}: creates the account for the player's UUID. */
public final class RegisterCommand implements CommandExecutor {

    private final AuthService auth;

    public RegisterCommand(final AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("Only players can register.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            auth.requestRegister(player);
            return true;
        }
        if (args.length == 1) {
            player.sendMessage(Component.text(
                    "Run /" + label + " without arguments and type the password twice in chat.",
                    NamedTextColor.RED));
            return true;
        }

        auth.register(player, args[0], CommandSupport.join(args, 1));
        return true;
    }
}
