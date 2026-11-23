package io.github.near.sweats.chat;

import io.github.near.sweats.SweatsPlugin;
import io.papermc.paper.event.player.AsyncChatDecorateEvent;
import java.util.List;
import java.util.regex.Pattern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Highlights online player names in chat.
 *
 * <p>Hooks {@link AsyncChatDecorateEvent}, which Paper fires before chat formatting plugins build
 * their final message. Recoloring only the name means rank prefixes, message colors, emoji
 * replacements and other chat plugins keep working exactly as before.
 */
public final class MentionHighlighter implements Listener {

    private final SweatsPlugin plugin;
    private final OnlinePlayerNames onlineNames;

    private volatile MentionSettings settings;
    private volatile CachedMention cached;

    public MentionHighlighter(final SweatsPlugin plugin, final OnlinePlayerNames onlineNames) {
        this.plugin = plugin;
        this.onlineNames = onlineNames;
        this.settings = MentionSettings.from(plugin.getConfig());
    }

    /** Re-reads the mention settings. The name snapshot and pattern are unaffected. */
    public void reload() {
        this.settings = MentionSettings.from(plugin.getConfig());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDecorate(final AsyncChatDecorateEvent event) {
        final MentionSettings current = settings;
        if (!current.enabled()) {
            return;
        }

        final CachedMention mention = patternFor(onlineNames.names());
        if (mention == null) {
            return;
        }

        event.result(MentionRenderer.highlight(event.result(), mention.pattern(), current.color()));
    }

    private CachedMention patternFor(final List<String> names) {
        final CachedMention local = cached;
        if (local != null && local.names() == names) {
            return local;
        }

        final Pattern built = MentionRenderer.pattern(names);
        if (built == null) {
            return null;
        }

        final CachedMention next = new CachedMention(names, built);
        cached = next;
        return next;
    }

    private record CachedMention(List<String> names, Pattern pattern) {}
}
