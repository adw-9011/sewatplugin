package io.github.near.sweats.commands;

import io.github.near.sweats.moderation.DurationFormat;
import io.github.near.sweats.moderation.DurationParser;
import io.github.near.sweats.moderation.Issuer;
import io.github.near.sweats.moderation.ModerationException;
import io.github.near.sweats.moderation.ModerationService;
import io.github.near.sweats.moderation.Punishment;
import io.github.near.sweats.moderation.PunishmentDuration;
import io.github.near.sweats.moderation.PunishmentMessages;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.player.PlayerKickEvent;

public final class BanCommand implements CommandExecutor, TabCompleter {

    private static final List<String> DURATIONS =
            List.of("30s", "10m", "2h", "7d", "2w", "1mo", "permanent");

    private final ModerationService moderation;
    private final PermissionService permissions;
    private final RankService ranks;

    public BanCommand(
            final ModerationService moderation,
            final PermissionService permissions,
            final RankService ranks) {
        this.moderation = moderation;
        this.permissions = permissions;
        this.ranks = ranks;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.BAN)) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(CommandSupport.error(
                    "Usage: /" + label + " <player|@a|@r|@p> <duration> <reason>"));
            return true;
        }

        final List<PlayerLookup.Target> targets = CommandSupport.resolveTargets(sender, args[0]);
        if (targets == null) {
            return true;
        }
        final boolean bulk = CommandSupport.isSelector(args[0]);

        final PunishmentDuration duration;
        try {
            duration = DurationParser.parse(args[1]);
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
            return true;
        }
        final String reason = CommandSupport.join(args, 2);
        final String length = duration.permanent()
                ? " permanently."
                : " for " + DurationFormat.human(duration.millis()) + ".";

        int done = 0;
        int skipped = 0;
        for (final PlayerLookup.Target target : targets) {
            final String denial = CommandSupport.punishmentDenial(sender, target.id(), ranks);
            if (denial != null) {
                if (!bulk) {
                    sender.sendMessage(CommandSupport.error(denial));
                    return true;
                }
                skipped++;
                continue;
            }

            try {
                final Punishment punishment = moderation.ban(
                        target.id(), target.name(), duration, reason, Issuer.of(sender));
                if (target.online() != null) {
                    target.online().kick(
                            PunishmentMessages.ban(punishment, moderation.now()),
                            PlayerKickEvent.Cause.BANNED);
                }
                done++;
                if (!bulk) {
                    sender.sendMessage(CommandSupport.success("Banned " + target.name() + length));
                }
            } catch (final ModerationException exception) {
                if (!bulk) {
                    sender.sendMessage(CommandSupport.error(exception.getMessage()));
                    return true;
                }
                skipped++;
            }
        }

        if (bulk) {
            sender.sendMessage(CommandSupport.success("Banned "
                    + CommandSupport.plural(done, "player") + length
                    + (skipped == 0 ? "" : " Skipped " + skipped
                            + " (yourself, higher rank or not allowed).")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.BAN)) {
            return List.of();
        }
        return switch (args.length) {
            case 1 -> CommandSupport.playerSuggestions(args[0]);
            case 2 -> CommandSupport.matching(DURATIONS, args[1]);
            default -> List.of();
        };
    }
}
