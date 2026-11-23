package io.github.near.sweats.pm;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.commands.CommandSupport;
import io.github.near.sweats.moderation.ModerationService;
import io.github.near.sweats.moderation.Punishment;
import io.github.near.sweats.moderation.PunishmentMessages;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /msg <player> <message>} and {@code /r <message>}. */
public final class MessageCommand implements CommandExecutor, TabCompleter {

    private final MessagingService messages;
    private final AuthService auth;
    private final PermissionService permissions;
    private final ModerationService moderation;

    public MessageCommand(
            final MessagingService messages,
            final AuthService auth,
            final PermissionService permissions,
            final ModerationService moderation) {
        this.messages = messages;
        this.auth = auth;
        this.permissions = permissions;
        this.moderation = moderation;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.MESSAGE)) {
            return true;
        }
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        final Optional<Punishment> mute = moderation.activeMute(player.getUniqueId());
        if (mute.isPresent()) {
            player.sendMessage(PunishmentMessages.mute(mute.get(), moderation.now()));
            return true;
        }

        final boolean reply = "r".equalsIgnoreCase(label) || "reply".equalsIgnoreCase(label);
        if (reply) {
            if (args.length == 0) {
                sender.sendMessage(error("Usage: /" + label + " <message>"));
                return true;
            }
            report(sender, messages.reply(player, CommandSupport.join(args, 0)));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(error("Usage: /" + label + " <player> <message>"));
            return true;
        }
        report(sender, messages.send(player, args[0], CommandSupport.join(args, 1)));
        return true;
    }

    private void report(final CommandSender sender, final MessagingService.Outcome outcome) {
        switch (outcome.result()) {
            case SENT -> {
                // Both sides already received the message.
            }
            case DISABLED -> sender.sendMessage(error("Private messages are disabled."));
            case EMPTY -> sender.sendMessage(error("You cannot send an empty message."));
            case TOO_LONG -> sender.sendMessage(error(
                    "Message is too long (max " + messages.maxLength() + " characters)."));
            case SELF -> sender.sendMessage(error("You cannot message yourself."));
            case UNKNOWN, OFFLINE, HIDDEN ->
                    sender.sendMessage(error("That player is not online."));
            case COOLDOWN -> sender.sendMessage(error(
                    "Wait " + outcome.cooldownRemainingSeconds() + "s before sending another message."));
            case NO_REPLY -> sender.sendMessage(error("You have no one to reply to."));
        }
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if ("r".equalsIgnoreCase(alias) || "reply".equalsIgnoreCase(alias) || args.length != 1) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        try {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                if (sender instanceof final Player viewer && !viewer.canSee(player)) {
                    continue;
                }
                if (player.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    names.add(player.getName());
                }
            }
        } catch (final RuntimeException noServer) {
            return List.of();
        }
        return names;
    }

    private static Component error(final String message) {
        return Component.text(message, NamedTextColor.RED);
    }
}
