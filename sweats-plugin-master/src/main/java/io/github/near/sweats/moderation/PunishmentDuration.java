package io.github.near.sweats.moderation;

public record PunishmentDuration(long millis, boolean permanent) {

    public PunishmentDuration {
        if (permanent && millis != 0L) {
            throw new IllegalArgumentException("A permanent punishment cannot have a duration.");
        }
        if (!permanent && millis <= 0L) {
            throw new IllegalArgumentException("Punishment duration must be positive.");
        }
    }

    public static PunishmentDuration ofPermanent() {
        return new PunishmentDuration(0L, true);
    }

    public long expiresAt(final long now) {
        return permanent ? 0L : Math.addExact(now, millis);
    }
}
