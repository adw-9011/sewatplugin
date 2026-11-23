package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.tpa.TpaService;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Declines a teleport request. */
public final class TpdenyCommand implements CommandExecutor, TabCompleter {

    private final TpaService tpa;
    private final PermissionService permissions;
    private final AuthService auth;

    public TpdenyCommand(
            final TpaService tpa,
            final PermissionService permissions,
            final AuthService auth) {
        this.tpa = tpa;
        this.permissions = permissions;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.TPA)) {
            return true;
        }
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " [player]"));
            return true;
        }
        try {
            tpa.deny(player, args.length == 1 ? args[0] : null);
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (args.length != 1 || !(sender instanceof final Player player)) {
            return List.of();
        }
        return tpa.incomingNames(player.getUniqueId(), args[0]);
    }
}
