package io.github.near.sweats.nick;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** The nickname table: one entry per player UUID, kept in {@code nicknames.yml}. */
final class NickStore {

    private final YamlFile file;
    private final Map<UUID, StoredNick> players = new ConcurrentHashMap<>();

    NickStore(final Path file) {
        this.file = new YamlFile(file);
    }

    void load(final Consumer<String> warn) {
        players.clear();

        final boolean existed = file.exists();
        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            if (!existed) {
                save(warn);
            }
            return;
        }

        final ConfigurationSection section = config.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (final String rawId : section.getKeys(false)) {
            final UUID id = parseUuid(rawId);
            if (id == null) {
                warn.accept("Skipped invalid nickname id '" + rawId + "' in " + file.path());
                continue;
            }
            final String name = section.getString(rawId + ".name", "");
            final String nickname = section.getString(rawId + ".nickname", "");
            if (nickname.isBlank()) {
                continue;
            }
            players.put(id, new StoredNick(name, nickname));
        }
    }

    boolean save(final Consumer<String> warn) {
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Sweats nicknames. The ~ prefix is added when the nickname is displayed."));

        final List<Map.Entry<UUID, StoredNick>> entries = new ArrayList<>(players.entrySet());
        entries.sort(Comparator.comparing(
                entry -> entry.getValue().name(), String.CASE_INSENSITIVE_ORDER));
        for (final Map.Entry<UUID, StoredNick> entry : entries) {
            final String path = "players." + entry.getKey();
            config.set(path + ".name", entry.getValue().name());
            config.set(path + ".nickname", entry.getValue().nickname());
        }

        return file.save(config, warn);
    }

    Optional<StoredNick> find(final UUID id) {
        return Optional.ofNullable(players.get(id));
    }

    void put(final UUID id, final String name, final String nickname) {
        players.put(id, new StoredNick(name, nickname));
    }

    boolean remove(final UUID id) {
        return players.remove(id) != null;
    }

    Optional<Entry> findByName(final String name) {
        for (final Map.Entry<UUID, StoredNick> entry : players.entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(name)) {
                return Optional.of(new Entry(entry.getKey(), entry.getValue().name()));
            }
        }
        return Optional.empty();
    }

    Optional<Entry> findByNickname(final String nickname) {
        for (final Map.Entry<UUID, StoredNick> entry : players.entrySet()) {
            if (entry.getValue().nickname().equalsIgnoreCase(nickname)) {
                return Optional.of(new Entry(entry.getKey(), entry.getValue().name()));
            }
        }
        return Optional.empty();
    }

    boolean nicknameTakenByOther(final UUID id, final String nickname) {
        for (final Map.Entry<UUID, StoredNick> entry : players.entrySet()) {
            if (!entry.getKey().equals(id)
                    && entry.getValue().nickname().equalsIgnoreCase(nickname)) {
                return true;
            }
        }
        return false;
    }

    boolean rename(final UUID id, final String name) {
        final StoredNick entry = players.get(id);
        if (entry == null || entry.name().equals(name)) {
            return false;
        }
        players.put(id, new StoredNick(name, entry.nickname()));
        return true;
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    record StoredNick(String name, String nickname) {}

    record Entry(UUID id, String name) {}
}
