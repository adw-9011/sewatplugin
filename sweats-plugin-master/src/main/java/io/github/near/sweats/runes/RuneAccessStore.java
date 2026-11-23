package io.github.near.sweats.runes;

import io.github.near.sweats.storage.YamlFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persistent Rune Table access and permanent boss-drop bonuses.
 *
 * <p>Data lives in {@code plugins/Sweats/runes/access.yml}. If the old standalone
 * {@code plugins/SweatyRunes/access.yml} exists and the new file does not, it is imported once.
 */
public final class RuneAccessStore {

    private final YamlFile file;
    private final Consumer<String> warn;
    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();

    public RuneAccessStore(final Path dataFolder, final Consumer<String> warn) {
        this.file = new YamlFile(dataFolder.resolve("runes").resolve("access.yml"));
        this.warn = warn;
        importLegacy(dataFolder);
        load();
    }

    private void importLegacy(final Path dataFolder) {
        if (file.exists() || dataFolder.getParent() == null) {
            return;
        }
        final Path legacy = dataFolder.getParent()
                .resolve("SweatyRunes")
                .resolve("access.yml");
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try {
            final Path parent = file.path().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(legacy, file.path());
            warn.accept("Imported rune access data from " + legacy + ".");
        } catch (final IOException exception) {
            warn.accept("Could not import " + legacy + ": " + exception.getMessage());
        }
    }

    public void load() {
        players.clear();
        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            return;
        }
        final ConfigurationSection section = config.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(false)) {
            final UUID id = parseUuid(key);
            if (id == null) {
                warn.accept("Skipped invalid rune access id '" + key + "' in " + file.path());
                continue;
            }
            final boolean access = section.getBoolean(key + ".access", false);
            final double bonus = Math.max(0.0, section.getDouble(key + ".bonus", 0.0));
            if (access || bonus > 0.0) {
                players.put(id, new PlayerData(access, bonus));
            }
        }
    }

    public void reload() {
        load();
    }

    public boolean has(final UUID id) {
        final PlayerData data = players.get(id);
        return data != null && data.access();
    }

    public boolean setAccess(final UUID id, final boolean access) {
        final PlayerData current = players.getOrDefault(id, PlayerData.NONE);
        if (current.access() == access) {
            return true;
        }
        return store(id, new PlayerData(access, current.bonus()));
    }

    public Set<UUID> granted() {
        final Set<UUID> ids = new LinkedHashSet<>();
        players.forEach((id, data) -> {
            if (data.access()) {
                ids.add(id);
            }
        });
        return ids;
    }

    public double bonus(final UUID id) {
        final PlayerData data = players.get(id);
        return data == null ? 0.0 : data.bonus();
    }

    public boolean setBonus(final UUID id, final double value) {
        final double bonus = Math.max(0.0, value);
        final PlayerData current = players.getOrDefault(id, PlayerData.NONE);
        if (Double.compare(current.bonus(), bonus) == 0) {
            return true;
        }
        return store(id, new PlayerData(current.access(), bonus));
    }

    /** Mutates the map and rolls the entry back if the file write fails. */
    private boolean store(final UUID id, final PlayerData data) {
        final PlayerData previous = players.get(id);
        if (!data.access() && data.bonus() <= 0.0) {
            players.remove(id);
        } else {
            players.put(id, data);
        }
        if (save()) {
            return true;
        }
        if (previous == null) {
            players.remove(id);
        } else {
            players.put(id, previous);
        }
        return false;
    }

    /** @return {@code false} when the access file could not be written */
    public boolean save() {
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Rune Table access and permanent boss-rune drop bonuses.",
                "Managed by /runeaccess and /runepermdropchance."));

        final List<Map.Entry<UUID, PlayerData>> entries = new ArrayList<>(players.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        for (final Map.Entry<UUID, PlayerData> entry : entries) {
            final String path = "players." + entry.getKey();
            if (entry.getValue().access()) {
                config.set(path + ".access", true);
            }
            if (entry.getValue().bonus() > 0.0) {
                config.set(path + ".bonus", entry.getValue().bonus());
            }
        }
        return file.save(config, warn);
    }

    public void close() {
        save();
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    private record PlayerData(boolean access, double bonus) {
        private static final PlayerData NONE = new PlayerData(false, 0.0);
    }
}
