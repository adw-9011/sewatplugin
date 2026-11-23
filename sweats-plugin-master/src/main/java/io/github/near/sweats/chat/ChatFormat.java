package io.github.near.sweats.chat;

import io.github.near.sweats.rank.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/** Shared rank/name/message formatting for public, team and member chat. */
public final class ChatFormat {

    private ChatFormat() {}

    public static Component ranked(
            final Rank rank,
            final String displayName,
            final Component message,
            final TextColor plusColor,
            final boolean whiteMessage) {
        final Component text = message.color(whiteMessage ? NamedTextColor.WHITE : rank.color());
        return Component.text()
                .append(rank.displayPrefix(plusColor))
                .append(Component.text(displayName, rank.color()))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(text)
                .build();
    }

    public static Component channel(
            final String label,
            final NamedTextColor color,
            final Component message) {
        return Component.text("[" + label + "] ", color).append(message);
    }
}
