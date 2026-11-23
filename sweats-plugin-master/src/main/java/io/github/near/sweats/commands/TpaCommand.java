package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.tpa.TpaService;
import java.util.List;
import java.util.Optional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Sends a teleport request to another player. */
public final class TpaCommand implements CommandExecutor, TabCompleter {

    private final TpaService tpa;
    private final PermissionService permissions;
    private final AuthService auth;

    public TpaCommand(
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
        if (args.length != 1) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " <player>"));
            return true;
        }
        final Optional<PlayerLookup.Target> found = PlayerLookup.find(args[0], sender);
        if (found.isEmpty() || found.get().online() == null) {
            sender.sendMessage(CommandSupport.error("That player is not online."));
            return true;
        }
        try {
            tpa.request(
                    player,
                    found.get().online(),
                    permissions.has(sender, PermissionNodes.TPA_BYPASS_COOLDOWN));
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
        if (args.length != 1) {
            return List.of();
        }
        return CommandSupport.matching(CommandSupport.onlineNames(args[0], sender), args[0]);
    }
}
