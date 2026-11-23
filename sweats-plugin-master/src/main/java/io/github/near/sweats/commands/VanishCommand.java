package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.vanish.VanishService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Enables vanish for operators and DEV staff. */
public final class VanishCommand implements CommandExecutor {

    private final VanishService vanish;
    private final PermissionService permissions;
    private final AuthService auth;

    public VanishCommand(
            final VanishService vanish,
            final PermissionService permissions,
            final AuthService auth) {
        this.vanish = vanish;
        this.permissions = permissions;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.VANISH)) {
            return true;
        }
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        vanish.vanish(player);
        return true;
    }
}
