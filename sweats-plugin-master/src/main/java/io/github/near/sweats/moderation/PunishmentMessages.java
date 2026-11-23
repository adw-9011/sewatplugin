package io.github.near.sweats.moderation;

import io.github.near.sweats.display.DisplayTheme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class PunishmentMessages {

    private static final String BORDER_TEXT = "=".repeat(36);

    private PunishmentMessages() {}

    public static Component ban(final Punishment punishment, final long now) {
        return Component.text("You are banned from Sweats.", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Reason: ", DisplayTheme.BLUE))
                .append(Component.text(punishment.reason(), DisplayTheme.TEXT))
                .append(Component.newline())
                .append(Component.text("Duration: ", DisplayTheme.BLUE))
                .append(Component.text(DurationFormat.remaining(punishment, now), DisplayTheme.SECONDARY));
    }

    public static Component kick(final Punishment punishment) {
        return Component.text("You were kicked from Sweats.", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Reason: ", DisplayTheme.BLUE))
                .append(Component.text(punishment.reason(), DisplayTheme.TEXT));
    }

    public static Component mute(final Punishment punishment, final long now) {
        final Component border = Component.text(BORDER_TEXT, DisplayTheme.PURPLE);
        final String duration = punishment.permanent()
                ? "Permanent"
                : DurationFormat.human(Math.max(0L, punishment.expiresAt() - now));

        return border
                .append(Component.newline())
                .append(Component.text(
                        "You are muted, you can't send any messages!",
                        DisplayTheme.TEXT,
                        TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Mute duration: ", DisplayTheme.BLUE))
                .append(Component.text(duration, DisplayTheme.SECONDARY))
                .append(Component.newline())
                .append(Component.text("Reason: ", DisplayTheme.BLUE))
                .append(Component.text(punishment.reason(), DisplayTheme.TEXT))
                .append(Component.newline())
                .append(border);
    }
}
