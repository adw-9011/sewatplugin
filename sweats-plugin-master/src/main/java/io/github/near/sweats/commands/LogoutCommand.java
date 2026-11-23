package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /logout}: clears the session and moves the player back to the login area. */
public final class LogoutCommand implements CommandExecutor {

    private final AuthService auth;

    public LogoutCommand(final AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("Only players can log out.", NamedTextColor.RED));
            return true;
        }
        auth.logout(player);
        return true;
    }
}
