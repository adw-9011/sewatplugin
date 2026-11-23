package io.github.near.sweats.moderation;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

final class PunishmentStore {

    private final YamlFile file;

    PunishmentStore(final Path file) {
        this.file = new YamlFile(file);
    }

    List<Punishment> load(final Consumer<String> warn) {
        final boolean existed = file.exists();
        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            if (!existed) {
                save(List.of(), warn);
            }
            return List.of();
        }

        final ConfigurationSection section = config.getConfigurationSection("punishments");
        if (section == null) {
            return List.of();
        }

        final List<Punishment> punishments = new ArrayList<>();
        for (final String rawId : section.getKeys(false)) {
            final Punishment punishment = read(section, rawId, warn);
            if (punishment != null) {
                punishments.add(punishment);
            }
        }
        return punishments;
    }

    boolean save(final Collection<Punishment> punishments, final Consumer<String> warn) {
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Sweats punishment history. Expired and revoked entries are kept for auditing.",
                "Expiry is a timestamp; no scheduled task is required."));

        final List<Punishment> sorted = new ArrayList<>(punishments);
        sorted.sort(Comparator.comparingLong(Punishment::issuedAt).thenComparing(Punishment::id));
        for (final Punishment punishment : sorted) {
            final String path = "punishments." + punishment.id();
            config.set(path + ".type", punishment.type().name());
            config.set(path + ".target", punishment.targetId().toString());
            config.set(path + ".target-name", punishment.targetName());
            if (punishment.issuerId() != null) {
                config.set(path + ".issuer", punishment.issuerId().toString());
            }
            config.set(path + ".issuer-name", punishment.issuerName());
            config.set(path + ".reason", punishment.reason());
            config.set(path + ".issued-at", punishment.issuedAt());
            if (punishment.expiresAt() != null) {
                config.set(path + ".expires-at", punishment.expiresAt());
            }
            config.set(path + ".revoked", punishment.revoked());
        }

        return file.save(config, warn);
    }

    private static Punishment read(
            final ConfigurationSection section, final String rawId, final Consumer<String> warn) {
        final UUID id = parseUuid(rawId);
        if (id == null) {
            warn.accept("Skipped punishment with invalid id '" + rawId + "'");
            return null;
        }

        try {
            final PunishmentType type = PunishmentType.valueOf(
                    section.getString(rawId + ".type", "").toUpperCase(Locale.ROOT));
            final UUID targetId = parseUuid(section.getString(rawId + ".target"));
            final String targetName = section.getString(rawId + ".target-name");
            final UUID issuerId = parseUuid(section.getString(rawId + ".issuer"));
            final String issuerName = section.getString(rawId + ".issuer-name", "Console");
            final String reason = section.getString(rawId + ".reason");
            final long issuedAt = section.getLong(rawId + ".issued-at");
            final Long expiresAt = section.contains(rawId + ".expires-at")
                    ? section.getLong(rawId + ".expires-at")
                    : null;
            final boolean revoked = section.getBoolean(rawId + ".revoked", false);

            if (targetId == null || targetName == null || reason == null) {
                warn.accept("Skipped incomplete punishment '" + rawId + "'");
                return null;
            }

            return new Punishment(
                    id, type, targetId, targetName, issuerId, issuerName, reason,
                    issuedAt, expiresAt, revoked);
        } catch (final RuntimeException exception) {
            warn.accept("Skipped invalid punishment '" + rawId + "': " + exception.getMessage());
            return null;
        }
    }

    private static UUID parseUuid(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }
}
