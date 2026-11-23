package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /login}.
 *
 * <p>With no arguments the password is read from the next chat message, so it never reaches the
 * server log. Arguments are still accepted for compatibility, but Paper writes them to the log
 * before any plugin can stop it.
 */
public final class LoginCommand implements CommandExecutor {

    private final AuthService auth;

    public LoginCommand(final AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("Only players can log in.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            auth.requestLogin(player);
            return true;
        }

        auth.loginFromCommand(player, CommandSupport.join(args, 0));
        return true;
    }
}
