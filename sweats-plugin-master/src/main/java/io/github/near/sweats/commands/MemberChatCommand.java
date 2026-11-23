package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.chat.ChatFormat;
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
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Private chat for Sweat and Sweat+. Recipients are checked one by one at send time, so a rank
 * change takes effect on the very next message and an ordinary player never receives the channel.
 */
public final class MemberChatCommand implements CommandExecutor {

    private final PermissionService permissions;
    private final AuthService auth;
    private final RankService ranks;
    private final NickService nicks;
    private final PerkService perks;
    private final ModerationService moderation;

    public MemberChatCommand(
            final PermissionService permissions,
            final AuthService auth,
            final RankService ranks,
            final NickService nicks,
            final PerkService perks,
            final ModerationService moderation) {
        this.permissions = permissions;
        this.auth = auth;
        this.ranks = ranks;
        this.nicks = nicks;
        this.perks = perks;
        this.moderation = moderation;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.CHAT_MEMBER)) {
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
        final String text = CommandSupport.join(args, 0);
        if (text.isBlank()) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " <message>"));
            return true;
        }

        final Rank rank = ranks.rankOf(player.getUniqueId());
        final Component message = ChatFormat.channel(
                "MEMBER",
                NamedTextColor.LIGHT_PURPLE,
                ChatFormat.ranked(
                        rank,
                        nicks.displayName(player.getUniqueId(), player.getName()),
                        Component.text(text),
                        perks.plusColor(player.getUniqueId()),
                        RankPerks.of(rank).whiteChat()));

        for (final Player online : Bukkit.getOnlinePlayers()) {
            if (!auth.isAuthenticated(online.getUniqueId())) {
                continue;
            }
            if (permissions.has(online.getUniqueId(), PermissionNodes.CHAT_MEMBER)) {
                online.sendMessage(message);
            }
        }
        return true;
    }
}
