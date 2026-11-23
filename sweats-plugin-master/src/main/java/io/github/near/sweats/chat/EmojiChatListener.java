package io.github.near.sweats.chat;

import io.github.near.sweats.SweatsPlugin;
import io.github.near.sweats.perks.RankPerks;
import io.github.near.sweats.rank.RankService;
import io.papermc.paper.event.player.AsyncChatDecorateEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Turns emoji codes such as {@code :dog:} into symbols in chat.
 *
 * <p>Hooks {@link AsyncChatDecorateEvent} like the mention highlighter, so it only touches the
 * message text and leaves rank prefixes and other chat formatting alone. It runs first so the
 * mention highlighter sees the finished message. Only codes allowed by the sender's rank are
 * replaced; everything else stays literal.
 */
public final class EmojiChatListener implements Listener {

    private final SweatsPlugin plugin;
    private final RankService ranks;
    private volatile boolean enabled;

    public EmojiChatListener(final SweatsPlugin plugin, final RankService ranks) {
        this.plugin = plugin;
        this.ranks = ranks;
        this.enabled = plugin.getConfig().getBoolean("emojis.enabled", true);
    }

    /** Re-reads {@code emojis.enabled} from the config. */
    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("emojis.enabled", true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDecorate(final AsyncChatDecorateEvent event) {
        if (!enabled) {
            return;
        }
        final RankPerks perks = RankPerks.of(ranks.rankOf(event.player().getUniqueId()));
        event.result(Emojis.replace(event.result(), Emojis.codes(perks.emojiLimit())));
    }
}
