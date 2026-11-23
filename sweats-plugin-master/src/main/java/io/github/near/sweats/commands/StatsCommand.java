package io.github.near.sweats.commands;

import io.github.near.sweats.stats.StatsMenu;
import io.github.near.sweats.stats.StatsService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /stats [player]} - opens a chest menu with a player's statistics.
 *
 * <p>Without an argument it shows your own stats. Works for offline players too, but the vanilla
 * counters (playtime, kills, ...) can only be read while the player is online.
 */
public final class StatsCommand implements CommandExecutor, TabCompleter {

    private final StatsService stats;
    private final PermissionService permissions;

    public StatsCommand(final StatsService stats, final PermissionService permissions) {
        this.stats = stats;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.STATS)) {
            return true;
        }
        if (!(sender instanceof final Player viewer)) {
            sender.sendMessage(Component.text(
                    "Only players can open the stats menu.", NamedTextColor.RED));
            return true;
        }

        if (args.length > 1) {
            viewer.sendMessage(Component.text("Usage: /" + label + " [player]", NamedTextColor.RED));
            return true;
        }

        final String requested = args.length == 0 ? viewer.getName() : args[0];
        final OfflinePlayer target = lookup(requested);
        if (target == null) {
            viewer.sendMessage(Component.text(
                    "No player named '" + requested + "' has played here.", NamedTextColor.RED));
            return true;
        }

        new StatsMenu(stats.snapshot(target), target).open(viewer);
        return true;
    }

    /** Online players first, then anyone the server still has player data for. */
    private static OfflinePlayer lookup(final String name) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }

        final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.hasPlayedBefore()) {
            return cached;
        }

        // The usercache is not always loaded, so fall back to the player data on disk.
        final OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline : null;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.STATS)) {
            return List.of();
        }
        if (args.length != 1) {
            return List.of();
        }

        final String prefix = args[0].toLowerCase(Locale.ROOT);
        final Set<String> names = new LinkedHashSet<>();
        Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        names.addAll(stats.knownNames());

        final List<String> matches = new ArrayList<>();
        for (final String name : names) {
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(name);
            }
        }
        return matches;
    }
}