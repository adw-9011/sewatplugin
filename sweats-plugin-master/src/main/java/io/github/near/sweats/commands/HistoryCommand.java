package io.github.near.sweats.commands;

import io.github.near.sweats.moderation.DurationFormat;
import io.github.near.sweats.moderation.ModerationService;
import io.github.near.sweats.moderation.Punishment;
import io.github.near.sweats.moderation.PunishmentType;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class HistoryCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ModerationService moderation;
    private final PermissionService permissions;

    public HistoryCommand(final ModerationService moderation, final PermissionService permissions) {
        this.moderation = moderation;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.HISTORY)) {
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " <player>"));
            return true;
        }

        final Optional<PlayerLookup.Target> target = PlayerLookup.find(args[0]);
        final List<Punishment> punishments = target
                .map(found -> moderation.history(found.id()))
                .orElseGet(() -> moderation.historyByName(args[0]));
        if (punishments.isEmpty()) {
            sender.sendMessage(CommandSupport.error("No punishments found for " + args[0] + "."));
            return true;
        }

        final String name = target.map(PlayerLookup.Target::name)
                .orElseGet(() -> punishments.getFirst().targetName());
        sender.sendMessage(CommandSupport.success("Punishment history for " + name + ":"));
        for (final Punishment punishment : punishments) {
            sender.sendMessage(Component.text(
                    DATE.format(Instant.ofEpochMilli(punishment.issuedAt()))
                            + " " + punishment.type()
                            + " " + punishment.status(moderation.now())
                            + " - " + punishment.reason()
                            + " (by " + punishment.issuerName()
                            + ", " + remaining(punishment) + ")"));
        }
        return true;
    }

    private String remaining(final Punishment punishment) {
        if (punishment.type() == PunishmentType.KICK) {
            return "no duration";
        }
        return DurationFormat.remaining(punishment, moderation.now());
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.HISTORY) || args.length != 1) {
            return List.of();
        }
        return CommandSupport.onlineNames(args[0]);
    }
}
