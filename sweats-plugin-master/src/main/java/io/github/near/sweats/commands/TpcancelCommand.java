package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.tpa.TpaService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Cancels the sender's pending teleport request. */
public final class TpcancelCommand implements CommandExecutor {

    private final TpaService tpa;
    private final PermissionService permissions;
    private final AuthService auth;

    public TpcancelCommand(
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
        try {
            tpa.cancel(player);
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }
}
