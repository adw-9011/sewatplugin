package io.github.near.sweats.commands;

import io.github.near.sweats.moderation.ModerationException;
import io.github.near.sweats.moderation.ModerationService;
import io.github.near.sweats.moderation.Punishment;
import io.github.near.sweats.moderation.PunishmentType;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class UnbanCommand implements CommandExecutor, TabCompleter {

    private final ModerationService moderation;
    private final PermissionService permissions;

    public UnbanCommand(final ModerationService moderation, final PermissionService permissions) {
        this.moderation = moderation;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.UNBAN)) {
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " <player>"));
            return true;
        }

        final Optional<UUID> id = targetId(args[0]);
        if (id.isEmpty()) {
            sender.sendMessage(CommandSupport.error(
                    "No active ban exists for " + args[0] + "."));
            return true;
        }

        try {
            final Punishment revoked = moderation.unban(id.get());
            sender.sendMessage(CommandSupport.success("Unbanned " + revoked.targetName() + "."));
        } catch (final ModerationException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }

    private Optional<UUID> targetId(final String name) {
        final Optional<PlayerLookup.Target> target = PlayerLookup.find(name);
        if (target.isPresent()) {
            return Optional.of(target.get().id());
        }
        return moderation.findActiveByName(name, PunishmentType.BAN)
                .map(Punishment::targetId);
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.UNBAN) || args.length != 1) {
            return List.of();
        }
        return CommandSupport.matching(moderation.activeNames(PunishmentType.BAN), args[0]);
    }
}
