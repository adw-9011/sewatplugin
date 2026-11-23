package io.github.near.sweats.afk;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Persists each player's lifetime AFK seconds in {@code afk.yml}. */
public final class AfkStore {

    private final YamlFile file;
    private final Map<UUID, Long> seconds = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private boolean dirty;

    public AfkStore(final Path file) {
        this.file = new YamlFile(file);
    }

    public void load(final Consumer<String> warn) {
        seconds.clear();
        names.clear();
        dirty = false;

        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            return;
        }
        final ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (final String key : players.getKeys(false)) {
            final UUID id = parseUuid(key);
            if (id == null) {
                continue;
            }
            final long value = players.getLong(key + ".afk-seconds", 0L);
            if (value > 0L) {
                seconds.put(id, value);
            }
            final String name = players.getString(key + ".name");
            if (name != null && !name.isBlank()) {
                names.put(id, name);
            }
        }
    }

    public long afkSeconds(final UUID id) {
        return seconds.getOrDefault(id, 0L);
    }

    public void addSeconds(final UUID id, final String name, final long amount) {
        if (id == null || amount <= 0L) {
            return;
        }
        seconds.merge(id, amount, Long::sum);
        if (name != null && !name.isBlank()) {
            names.put(id, name);
        }
        dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    public void save(final Consumer<String> warn) {
        if (!dirty) {
            return;
        }
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Lifetime AFK time counted by Sweats.",
                "Seconds are added after afk.timeout-seconds of no movement, chat or interaction."));

        final List<Map.Entry<UUID, Long>> entries = new ArrayList<>(seconds.entrySet());
        entries.sort(Comparator.comparing(
                entry -> names.getOrDefault(entry.getKey(), entry.getKey().toString()),
                String.CASE_INSENSITIVE_ORDER));
        for (final Map.Entry<UUID, Long> entry : entries) {
            final String path = "players." + entry.getKey();
            config.set(path + ".name", names.getOrDefault(entry.getKey(), entry.getKey().toString()));
            config.set(path + ".afk-seconds", entry.getValue());
        }

        if (file.save(config, warn)) {
            dirty = false;
        }
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }
}
