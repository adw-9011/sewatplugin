package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.perks.PerkService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Lets exactly the Sweat+ rank recolour the {@code +} in their prefix. */
public final class PlusColorCommand implements CommandExecutor, TabCompleter {

    private final PerkService perks;
    private final PermissionService permissions;
    private final AuthService auth;

    public PlusColorCommand(
            final PerkService perks,
            final PermissionService permissions,
            final AuthService auth) {
        this.perks = perks;
        this.permissions = permissions;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.PLUS_COLOR)) {
            return true;
        }
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        if (!perks.canCustomizePlusColor(player.getUniqueId())) {
            sender.sendMessage(CommandSupport.error(
                    "Only Sweat+ players can choose a + colour."));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(CommandSupport.error(
                    "Usage: /" + label + " <colour|reset>. Colours: "
                            + String.join(", ", perks.colorNames())));
            return true;
        }
        try {
            final boolean changed = perks.setPlusColor(player.getUniqueId(), args[0]);
            if (!changed) {
                sender.sendMessage(Component.text(
                        "Your + is already that colour.", NamedTextColor.GRAY));
                return true;
            }
            if (args[0].equalsIgnoreCase("reset")
                    || args[0].equalsIgnoreCase("off")
                    || args[0].equalsIgnoreCase("default")) {
                sender.sendMessage(Component.text(
                        "Your + colour was reset.", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text(
                        "Your + is now " + args[0].toLowerCase(java.util.Locale.ROOT) + ".",
                        NamedTextColor.GREEN));
            }
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
        if (args.length != 1
                || !(sender instanceof final Player player)
                || !permissions.has(sender, PermissionNodes.PLUS_COLOR)
                || !perks.canCustomizePlusColor(player.getUniqueId())) {
            return List.of();
        }
        final List<String> values = new java.util.ArrayList<>(perks.colorNames());
        values.add("reset");
        return CommandSupport.matching(values, args[0]);
    }
}
