package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.homes.HomeService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Saves the player's current position to a rank-unlocked home slot. */
public final class HomeSetCommand implements CommandExecutor, TabCompleter {

    private final HomeService homes;
    private final PermissionService permissions;
    private final AuthService auth;

    public HomeSetCommand(
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
        if (!CommandSupport.require(sender, permissions, PermissionNodes.HOMESET)) {
            return true;
        }
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " <slot>"));
            return true;
        }
        final int slot;
        try {
            slot = Integer.parseInt(args[0]);
        } catch (final NumberFormatException exception) {
            sender.sendMessage(CommandSupport.error("Slot must be a whole number."));
            return true;
        }
        homes.setHome(player, slot);
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
        if (!permissions.has(sender, PermissionNodes.HOMESET)) {
            return List.of();
        }
        final List<String> slots = new ArrayList<>();
        for (int slot = 1; slot <= homes.limit(player.getUniqueId()); slot++) {
            slots.add(Integer.toString(slot));
        }
        return CommandSupport.matching(slots, args[0]);
    }
}
