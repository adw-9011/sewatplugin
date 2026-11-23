package io.github.near.sweats.rank;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

final class RankStore {

    private final YamlFile file;
    private volatile State state = new State(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());

    RankStore(final Path file) {
        this.file = new YamlFile(file);
    }

    void load(final Consumer<String> warn) {
        final Map<String, StoredRank> loadedRanks = new ConcurrentHashMap<>();
        final Map<UUID, StoredAssignment> loadedPlayers = new ConcurrentHashMap<>();

        final boolean existed = file.exists();
        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            state = new State(loadedRanks, loadedPlayers);
            if (!existed) {
                save(warn);
            }
            return;
        }

        final ConfigurationSection rankSection = config.getConfigurationSection("ranks");
        if (rankSection != null) {
            for (final String rawKey : rankSection.getKeys(false)) {
                final String name = rankSection.getString(rawKey + ".name", rawKey);
                final String key = Rank.key(name);
                final String displayNameStr = rankSection.getString(rawKey + ".display-name", name);
                final Component displayName = LegacyComponentSerializer.legacySection().deserialize(displayNameStr);
                final int weight = rankSection.getInt(rawKey + ".weight", Integer.MIN_VALUE);
                if (key.isBlank() || name.isBlank() || displayName == null || weight < 0) {
                    warn.accept("Skipped invalid rank '" + rawKey + "' in " + file.path());
                    continue;
                }
                if (loadedRanks.containsKey(key)) {
                    warn.accept("Skipped duplicate rank '" + rawKey + "' in " + file.path());
                    continue;
                }
                loadedRanks.put(key, new StoredRank(name.trim(), displayName, weight));
            }
        }

        final ConfigurationSection playerSection = config.getConfigurationSection("players");
        if (playerSection != null) {
            for (final String rawId : playerSection.getKeys(false)) {
                final UUID id = parseUuid(rawId);
                if (id == null) {
                    warn.accept("Skipped invalid player id '" + rawId + "' in " + file.path());
                    continue;
                }
                final String name = playerSection.getString(rawId + ".name", rawId);
                final String rankKey = playerSection.getString(rawId + ".rank");
                if (name == null || name.isBlank() || rankKey == null || rankKey.isBlank()) {
                    warn.accept("Skipped invalid rank assignment for " + rawId + " in " + file.path());
                    continue;
                }
                final Instant expiresAt = parseInstant(playerSection.getString(rawId + ".expires-at"));
                loadedPlayers.put(id, new StoredAssignment(name.trim(), Rank.key(rankKey), expiresAt));
            }
        }

        state = new State(loadedRanks, loadedPlayers);
    }

    boolean save(final Consumer<String> warn) {
        final State current = state;
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Sweats ranks. Core ranks are built in and cannot be deleted.",
                "Player assignments use UUIDs; names are informational only.",
                "Rank names support legacy formatting codes (&0-9, &a-f, &k-o, &r).",
                "Player assignments may include an optional expires-at timestamp (ISO-8601 UTC)."));

        final List<Map.Entry<String, StoredRank>> rankEntries =
                new ArrayList<>(current.ranks().entrySet());
        rankEntries.sort(Comparator.comparing(entry -> entry.getValue().name(), String.CASE_INSENSITIVE_ORDER));
        for (final Map.Entry<String, StoredRank> entry : rankEntries) {
            final String path = "ranks." + entry.getKey();
            final StoredRank rank = entry.getValue();
            config.set(path + ".name", rank.name());
            config.set(path + ".display-name", LegacyComponentSerializer.legacySection().serialize(rank.displayName()));
            config.set(path + ".weight", rank.weight());
        }

        final List<Map.Entry<UUID, StoredAssignment>> playerEntries =
                new ArrayList<>(current.players().entrySet());
        playerEntries.sort(Comparator.comparing(entry -> entry.getValue().name(), String.CASE_INSENSITIVE_ORDER));
        for (final Map.Entry<UUID, StoredAssignment> entry : playerEntries) {
            final String path = "players." + entry.getKey();
            final StoredAssignment assignment = entry.getValue();
            config.set(path + ".name", assignment.name());
            config.set(path + ".rank", assignment.rankKey());
            if (assignment.expiresAt() != null) {
                config.set(path + ".expires-at", assignment.expiresAt().toString());
            }
        }

        return file.save(config, warn);
    }

    Map<String, StoredRank> ranks() {
        return state.ranks();
    }

    Map<UUID, StoredAssignment> players() {
        return state.players();
    }

    void putRank(final String key, final StoredRank rank) {
        state.ranks().put(key, rank);
    }

    void removeRank(final String key) {
        state.ranks().remove(key);
    }

    void putPlayer(final UUID id, final StoredAssignment assignment) {
        state.players().put(id, assignment);
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    private static Instant parseInstant(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (final Exception exception) {
            return null;
        }
    }

    private record State(
            Map<String, StoredRank> ranks,
            Map<UUID, StoredAssignment> players) {}

    record StoredRank(String name, Component displayName, int weight) {}

    record StoredAssignment(String name, String rankKey, Instant expiresAt) {
        StoredAssignment(String name, String rankKey) {
            this(name, rankKey, null);
        }
    }
}