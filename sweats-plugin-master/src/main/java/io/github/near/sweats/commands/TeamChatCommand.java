package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.chat.ChatFormat;
import io.github.near.sweats.logging.discord.DiscordLogService;
import io.github.near.sweats.moderation.ModerationService;
import io.github.near.sweats.moderation.Punishment;
import io.github.near.sweats.moderation.PunishmentMessages;
import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.perks.PerkService;
import io.github.near.sweats.perks.RankPerks;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.teams.TeamService;
import io.github.near.sweats.teams.TeamService.Team;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Private team chat. Recipients come from the team's member list, never from public chat. */
public final class TeamChatCommand implements CommandExecutor {

    private final TeamService teams;
    private final PermissionService permissions;
    private final AuthService auth;
    private final RankService ranks;
    private final NickService nicks;
    private final PerkService perks;
    private final ModerationService moderation;
    private final DiscordLogService discord;

    public TeamChatCommand(
            final TeamService teams,
            final PermissionService permissions,
            final AuthService auth,
            final RankService ranks,
            final NickService nicks,
            final PerkService perks,
            final ModerationService moderation,
            final DiscordLogService discord) {
        this.teams = teams;
        this.permissions = permissions;
        this.auth = auth;
        this.ranks = ranks;
        this.nicks = nicks;
        this.perks = perks;
        this.moderation = moderation;
        this.discord = discord;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.TEAM_CHAT)) {
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
        if (args.length == 0) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " <message>"));
            return true;
        }
        final Team team = teams.teamOf(player.getUniqueId()).orElse(null);
        if (team == null) {
            sender.sendMessage(CommandSupport.error("You are not in a team."));
            return true;
        }

        final String text = CommandSupport.join(args, 0);
        if (text.isBlank()) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " <message>"));
            return true;
        }
        final Rank rank = ranks.rankOf(player.getUniqueId());
        final String displayName =
                nicks.displayName(player.getUniqueId(), player.getName());
        final Component message = ChatFormat.channel(
                "TEAM",
                NamedTextColor.AQUA,
                ChatFormat.ranked(
                        rank,
                        displayName,
                        Component.text(text),
                        perks.plusColor(player.getUniqueId()),
                        RankPerks.of(rank).whiteChat()));

        for (final var member : team.members()) {
            final Player online = Bukkit.getPlayer(member);
            if (online != null && auth.isAuthenticated(member)) {
                online.sendMessage(message);
            }
        }
        discord.logTeamChat(team.name(), player.getName(), text);
        return true;
    }
}
