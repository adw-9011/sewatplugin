package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /changepass}: current password, then the new one.
 *
 * <p>Operators can reset someone else's password with {@code /changepass <player> <newPassword>}.
 * That form is only used when the sender has {@code sweats.auth.admin}; everyone else keeps the
 * normal two-argument form. Because a player with that permission could have an old password that
 * looks like a player name, operators change their own password with the no-argument flow.
 */
public final class ChangePasswordCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "sweats.auth.admin";

    private final AuthService auth;

    public ChangePasswordCommand(final AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        final boolean adminForm = args.length >= 2
                && sender.hasPermission(ADMIN_PERMISSION)
                && (args.length == 2 || !(sender instanceof Player));
        if (adminForm) {
            auth.adminChangePassword(sender, args[0], CommandSupport.join(args, 1));
            return true;
        }

        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text(
                    "Usage: /" + label + " <player> <newPassword>", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            auth.requestChangePassword(player);
            return true;
        }
        if (args.length == 1) {
            player.sendMessage(Component.text(
                    "Run /" + label + " without arguments and type the passwords in chat.",
                    NamedTextColor.RED));
            return true;
        }

        auth.changePassword(player, args[0], CommandSupport.join(args, 1));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (args.length == 1 && sender.hasPermission(ADMIN_PERMISSION)) {
            return CommandSupport.onlineNames(args[0]);
        }
        return List.of();
    }
}
