package io.github.near.sweats.commands;

import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Changes nicknames.
 *
 * <p>{@code /nick <nickname>} changes your own nickname; {@code /nick <player> <nickname>}
 * changes someone else's. Nicknames are displayed without any prefix.
 */
public final class NickCommand implements CommandExecutor, TabCompleter {

    private static final Set<String> CLEAR_WORDS = Set.of("off", "clear", "reset", "remove");

    private final NickService nicks;
    private final PermissionService permissions;

    public NickCommand(final NickService nicks, final PermissionService permissions) {
        this.nicks = nicks;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        if (args.length == 1) {
            // Self-service nickname change or removal
            if (!CommandSupport.require(sender, permissions, PermissionNodes.NICK)) {
                return true;
            }
            if (!(sender instanceof final Player player)) {
                sender.sendMessage(CommandSupport.error(
                        "Only players can change their own nickname. "
                                + "Use /" + label + " <player> <nickname>."));
                return true;
            }
            apply(sender, player.getUniqueId(), player.getName(), args[0]);
            return true;
        }

        // Administrative nickname change (for other players)
        if (!CommandSupport.require(sender, permissions, PermissionNodes.NICK_OTHERS)) {
            return true;
        }
        final Optional<PlayerLookup.Target> target = findTarget(args[1]);
        if (target.isEmpty()) {
            sender.sendMessage(CommandSupport.error(
                    "Unknown player: " + args[1] + ". They must have played on this server."));
            return true;
        }

        final String nickname = CommandSupport.join(args, 2);
        if (nickname.isBlank()) {
            usage(sender, label);
            return true;
        }
        apply(sender, target.get().id(), target.get().name(), nickname);
        return true;
    }

    private void apply(
            final CommandSender sender,
            final UUID id,
            final String playerName,
            final String nickname) {
        if (isClearWord(nickname)) {
            try {
                if (!nicks.clearNickname(id)) {
                    sender.sendMessage(CommandSupport.error(
                            playerName + " does not have a nickname."));
                    return;
                }
            } catch (final IllegalStateException exception) {
                sender.sendMessage(CommandSupport.error(exception.getMessage()));
                return;
            }
            sender.sendMessage(CommandSupport.success(
                    "Cleared " + playerName + "'s nickname."));
            return;
        }

        try {
            final boolean changed = nicks.setNickname(id, playerName, nickname);
            if (!changed) {
                sender.sendMessage(CommandSupport.error(
                        playerName + " already has that nickname."));
                return;
            }
            sender.sendMessage(CommandSupport.success(
                    "Set " + playerName + "'s nickname to "
                            + NickService.PREFIX + nicks.nickname(id).orElse(nickname) + "."));
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
    }

    private static boolean isClearWord(final String value) {
        return CLEAR_WORDS.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private Optional<PlayerLookup.Target> findTarget(final String name) {
        final Optional<PlayerLookup.Target> online = PlayerLookup.find(name);
        if (online.isPresent()) {
            return online;
        }
        return nicks.knownPlayer(name)
                .map(entry -> new PlayerLookup.Target(entry.id(), entry.name(), null));
    }

    private static void usage(final CommandSender sender, final String label) {
        sender.sendMessage(Component.text("Nickname commands:"));
        sender.sendMessage(Component.text(
                "/" + label + " <nickname> - change your own nickname (Sweat+ and above)"));
        sender.sendMessage(Component.text(
                "/" + label + " remove - remove your own nickname (Sweat+ and above)"));
        sender.sendMessage(Component.text(
                "/" + label + " <player> <nickname> - change another player's nickname (staff)"));
        sender.sendMessage(Component.text(
                "/" + label + " <player> remove - remove another player's nickname (staff)"));
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        // Self-service tab completion
        if (args.length == 1 && permissions.has(sender, PermissionNodes.NICK)) {
            final List<String> suggestions = new ArrayList<>(CommandSupport.onlineNames(args[0]));
            suggestions.addAll(CommandSupport.matching(List.of("off", "clear", "reset", "remove"), args[0]));
            return suggestions;
        }
        // Admin tab completion
        if (args.length == 2 && permissions.has(sender, PermissionNodes.NICK_OTHERS)) {
            return CommandSupport.matching(
                    List.of("off", "clear", "reset", "remove"), args[1]);
        }
        return List.of();
    }
}