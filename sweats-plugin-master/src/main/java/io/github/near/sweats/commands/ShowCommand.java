package io.github.near.sweats.commands;

import io.github.near.sweats.show.ShowService;
import io.github.near.sweats.show.ShowSlot;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /show [slot]} - show off what you are holding or wearing. */
public final class ShowCommand implements CommandExecutor, TabCompleter {

    private final ShowService showService;
    private final PermissionService permissions;

    public ShowCommand(final ShowService showService, final PermissionService permissions) {
        this.showService = showService;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.SHOW)) {
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("Only players can use /" + label + ".", NamedTextColor.RED));
            return true;
        }

        ShowSlot slot = ShowSlot.MAIN_HAND;
        if (args.length > 0) {
            final Optional<ShowSlot> parsed = ShowSlot.parse(args[0]);
            if (parsed.isEmpty()) {
                player.sendMessage(Component.text(
                        "Unknown slot '" + args[0] + "'. Use one of: " + slotList() + ".",
                        NamedTextColor.RED));
                return true;
            }
            slot = parsed.get();
        }

        showService.show(player, slot);
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.SHOW)) {
            return List.of();
        }
        if (args.length != 1) {
            return List.of();
        }

        final String prefix = args[0].toLowerCase(Locale.ROOT);
        return Arrays.stream(ShowSlot.values())
                .map(slot -> slot.aliases().getFirst())
                .filter(name -> name.startsWith(prefix))
                .toList();
    }

    private static String slotList() {
        return String.join(", ", Arrays.stream(ShowSlot.values())
                .map(slot -> slot.aliases().getFirst())
                .toList());
    }
}