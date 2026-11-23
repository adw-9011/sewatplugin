package io.github.near.sweats.ping;

import net.kyori.adventure.text.format.NamedTextColor;

public enum PingStatus {

    GOOD(NamedTextColor.GREEN),
    MODERATE(NamedTextColor.YELLOW),
    HIGH(NamedTextColor.RED);

    private final NamedTextColor color;

    PingStatus(final NamedTextColor color) {
        this.color = color;
    }

    public static PingStatus forPing(final int ping, final PingSettings settings) {
        final int safePing = Math.max(0, ping);
        if (safePing <= settings.goodMaxMillis()) {
            return GOOD;
        }
        return safePing <= settings.moderateMaxMillis() ? MODERATE : HIGH;
    }

    public NamedTextColor color() {
        return color;
    }
}