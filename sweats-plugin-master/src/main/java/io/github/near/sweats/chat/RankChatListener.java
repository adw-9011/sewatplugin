package io.github.near.sweats.chat;

import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.perks.PerkService;
import io.github.near.sweats.perks.RankPerks;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Adds the sender's rank in front of every chat message. */
public final class RankChatListener implements Listener {

    private final RankService ranks;
    private final NickService nicks;
    private final PerkService perks;

    public RankChatListener(
            final RankService ranks, final NickService nicks, final PerkService perks) {
        this.ranks = ranks;
        this.nicks = nicks;
        this.perks = perks;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(final AsyncChatEvent event) {
        final Rank rank = ranks.rankOf(event.getPlayer().getUniqueId());
        final String displayName =
                nicks.displayName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        final TextColor plusColor = perks.plusColor(event.getPlayer().getUniqueId());
        event.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, message) ->
                format(rank, displayName, message, plusColor, RankPerks.of(rank).whiteChat())));
    }

    static Component format(final Rank rank, final String playerName, final Component message) {
        return format(rank, playerName, message, null, RankPerks.of(rank).whiteChat());
    }

    static Component format(
            final Rank rank,
            final String playerName,
            final Component message,
            final TextColor plusColor,
            final boolean whiteMessage) {
        return ChatFormat.ranked(rank, playerName, message, plusColor, whiteMessage);
    }
}
