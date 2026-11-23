package io.github.near.sweats.commands;

import io.github.near.sweats.moderation.Issuer;
import io.github.near.sweats.moderation.ModerationException;
import io.github.near.sweats.moderation.ModerationService;
import io.github.near.sweats.moderation.Punishment;
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

public final class KickCommand implements CommandExecutor, TabCompleter {

    private final ModerationService moderation;
    private final PermissionService permissions;
    private final RankService ranks;

    public KickCommand(
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
        if (!CommandSupport.require(sender, permissions, PermissionNodes.KICK)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " <player|@a|@r|@p> <reason>"));
            return true;
        }

        final List<PlayerLookup.Target> targets = CommandSupport.resolveTargets(sender, args[0]);
        if (targets == null) {
            return true;
        }
        final boolean bulk = CommandSupport.isSelector(args[0]);
        final String reason = CommandSupport.join(args, 1);

        int kicked = 0;
        int skipped = 0;
        for (final PlayerLookup.Target target : targets) {
            if (target.online() == null) {
                if (!bulk) {
                    sender.sendMessage(CommandSupport.error(target.name() + " is not online."));
                    return true;
                }
                skipped++;
                continue;
            }

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
                final Punishment punishment = moderation.kick(
                        target.id(), target.name(), reason, Issuer.of(sender));
                target.online().kick(
                        PunishmentMessages.kick(punishment),
                        PlayerKickEvent.Cause.KICKED);
                kicked++;
                if (!bulk) {
                    sender.sendMessage(CommandSupport.success("Kicked " + target.name() + "."));
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
            sender.sendMessage(CommandSupport.success("Kicked "
                    + CommandSupport.plural(kicked, "player") + "."
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
        if (!permissions.has(sender, PermissionNodes.KICK)) {
            return List.of();
        }
        return args.length == 1
                ? CommandSupport.playerSuggestions(args[0])
                : List.of();
    }
}
