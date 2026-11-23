package io.github.near.sweats.commands;

import io.github.near.sweats.SweatsPlugin;
import io.github.near.sweats.mapart.MapArtService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /gif <image>} - turns an image from {@code plugins/Sweats/gifs/} into a mapart item.
 *
 * <p>Run without an argument to see which images are available.
 */
public final class MapArtCommand implements CommandExecutor, TabCompleter {

    private final SweatsPlugin plugin;
    private final MapArtService mapArt;
    private final PermissionService permissions;

    public MapArtCommand(final SweatsPlugin plugin, final MapArtService mapArt, final PermissionService permissions) {
        this.plugin = plugin;
        this.mapArt = mapArt;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.GIF)) {
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("Only players can use /" + label + ".", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            list(player, label);
            return true;
        }

        if (args.length > 1) {
            player.sendMessage(Component.text("Usage: /" + label + " <image>", NamedTextColor.RED));
            return true;
        }

        mapArt.give(player, args[0]).thenAccept(result -> send(player, result));
        return true;
    }

    private void send(final Player player, final MapArtService.Result result) {
        if (Bukkit.isPrimaryThread()) {
            if (player.isOnline()) {
                player.sendMessage(resultMessage(result));
            }
            return;
        }
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(resultMessage(result));
                }
            });
        }
    }

    private Component resultMessage(final MapArtService.Result result) {
        return switch (result.status()) {
            case GIVEN -> Component.text()
                    .append(Component.text("Gave you the mapart ", NamedTextColor.GRAY))
                    .append(Component.text(result.name(), NamedTextColor.WHITE))
                    .append(Component.text(".", NamedTextColor.GRAY))
                    .build();
            case UNKNOWN -> Component.text()
                    .append(Component.text("No image named '" + result.name() + "'.", NamedTextColor.RED))
                    .append(Component.newline())
                    .append(Component.text("Drop image files into " + mapArt.folderDisplay()
                            + " and run /sweats reload.", NamedTextColor.GRAY))
                    .build();
            case UNREADABLE -> Component.text(
                    "Could not read '" + result.name() + "' as an image. Use png, jpg, gif or bmp.",
                    NamedTextColor.RED);
            case DISABLED -> Component.text("/gif is disabled in config.yml (gifs.enabled).", NamedTextColor.RED);
            case OFFLINE -> Component.text("You went offline before the mapart was ready.", NamedTextColor.RED);
        };
    }

    private void list(final Player player, final String label) {
        if (!mapArt.enabled()) {
            player.sendMessage(Component.text("/gif is disabled in config.yml (gifs.enabled).", NamedTextColor.RED));
            return;
        }

        final List<String> names = mapArt.names();
        if (names.isEmpty()) {
            player.sendMessage(Component.text()
                    .append(Component.text("No images yet.", NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text(
                            "Drop png/jpg/gif files into " + mapArt.folderDisplay()
                                    + ", run /sweats reload, then /" + label + " <image>.",
                            NamedTextColor.GRAY))
                    .build());
            return;
        }

        player.sendMessage(Component.text()
                .append(Component.text("Images (" + names.size() + "): ", NamedTextColor.GRAY))
                .append(Component.text(String.join(", ", names), NamedTextColor.WHITE))
                .build());
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.GIF)) {
            return List.of();
        }
        if (args.length != 1) {
            return List.of();
        }

        final String prefix = args[0].toLowerCase(Locale.ROOT);
        return mapArt.names().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}