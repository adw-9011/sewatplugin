package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.homes.HomeService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens the home menu. */
public final class HomeCommand implements CommandExecutor {

    private final HomeService homes;
    private final PermissionService permissions;
    private final AuthService auth;

    public HomeCommand(
            final HomeService homes,
            final PermissionService permissions,
            final AuthService auth) {
        this.homes = homes;
        this.permissions = permissions;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.HOME)) {
            return true;
        }
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        homes.openMenu(player);
        return true;
    }
}
