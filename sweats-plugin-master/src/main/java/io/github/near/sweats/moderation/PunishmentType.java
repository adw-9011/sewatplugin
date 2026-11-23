package io.github.near.sweats.moderation;

public enum PunishmentType {
    BAN,
    MUTE,
    KICK;

    public boolean activeType() {
        return this != KICK;
    }
}
