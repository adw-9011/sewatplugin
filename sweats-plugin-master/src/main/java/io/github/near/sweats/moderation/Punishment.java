package io.github.near.sweats.moderation;

import java.util.Objects;
import java.util.UUID;

public record Punishment(
        UUID id,
        PunishmentType type,
        UUID targetId,
        String targetName,
        UUID issuerId,
        String issuerName,
        String reason,
        long issuedAt,
        Long expiresAt,
        boolean revoked) {

    public Punishment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(issuerName, "issuerName");
        Objects.requireNonNull(reason, "reason");
        if (targetName.isBlank() || issuerName.isBlank() || reason.isBlank()) {
            throw new IllegalArgumentException("Punishment names and reason cannot be blank.");
        }
        if (issuedAt <= 0L) {
            throw new IllegalArgumentException("Punishment issue time must be positive.");
        }
        if (expiresAt != null && expiresAt <= issuedAt) {
            throw new IllegalArgumentException("Punishment expiry must follow its issue time.");
        }
        if (type == PunishmentType.KICK && expiresAt != null) {
            throw new IllegalArgumentException("Kicks cannot expire.");
        }
    }

    public boolean permanent() {
        return expiresAt == null;
    }

    public boolean active(final long now) {
        return type.activeType() && !revoked && (expiresAt == null || expiresAt > now);
    }

    public String status(final long now) {
        if (type == PunishmentType.KICK) {
            return "RECORDED";
        }
        if (revoked) {
            return "REVOKED";
        }
        if (expiresAt != null && expiresAt <= now) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    public Punishment revoke() {
        return new Punishment(
                id, type, targetId, targetName, issuerId, issuerName, reason, issuedAt, expiresAt, true);
    }
}
