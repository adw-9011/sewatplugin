package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * A viewer-aware replacement for vanilla {@code /list}. Hidden (vanished or waiting) players are
 * not counted or named for ordinary players.
 */
public final class ListCommand implements CommandExecutor {

    private final PermissionService permissions;
    private final AuthService auth;

    public ListCommand(final PermissionService permissions, final AuthService auth) {
        this.permissions = permissions;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.LIST)) {
            return true;
        }
        if (sender instanceof final Player player && !auth.isAuthenticated(player.getUniqueId())) {
            sender.sendMessage(CommandSupport.error("You must log in first."));
            return true;
        }

        final List<String> names = new ArrayList<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (sender instanceof final Player viewer && !viewer.canSee(player)) {
                continue;
            }
            names.add(player.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        sender.sendMessage(Component.text(
                "There are " + names.size() + " player(s) online: " + String.join(", ", names),
                NamedTextColor.GRAY));
        return true;
    }
}
