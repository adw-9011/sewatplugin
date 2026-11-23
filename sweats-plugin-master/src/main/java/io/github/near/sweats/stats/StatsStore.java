package io.github.near.sweats.stats;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Counters for the statistics vanilla Minecraft does not keep - currently blocks placed.
 *
 * <p>The file is small and only rewritten when something changed, at most once a minute and on
 * shutdown, so placing blocks does not cause disk traffic.
 */
public final class StatsStore {

    private final Path file;
    private final Map<UUID, Long> blocksPlaced = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();

    private boolean dirty;
    private List<String> knownNamesCache;

    public StatsStore(final Path file) {
        this.file = file;
    }

    /** Reads the counters once, at startup. A missing file simply means nothing was placed yet. */
    public void load(final Consumer<String> warn) {
        blocksPlaced.clear();
        names.clear();
        dirty = false;
        knownNamesCache = null;

        if (!Files.isRegularFile(file)) {
            return;
        }

        final String text;
        try {
            text = Files.readString(file);
        } catch (final IOException exception) {
            warn.accept("Could not read " + file + ": " + exception.getMessage());
            return;
        }

        final YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (final InvalidConfigurationException | RuntimeException exception) {
            quarantine(warn, exception.getMessage());
            return;
        }

        final ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) {
            if (hasContent(text)) {
                quarantine(warn, "missing players section");
            }
            return;
        }

        for (final String key : players.getKeys(false)) {
            final UUID uuid = parseUuid(key);
            if (uuid == null) {
                continue;
            }
            final long placed = players.getLong(key + ".blocks-placed", 0L);
            if (placed > 0L) {
                blocksPlaced.put(uuid, placed);
            }
            final String name = players.getString(key + ".name");
            if (name != null && !name.isBlank()) {
                names.put(uuid, name);
            }
        }
    }

    public long blocksPlaced(final UUID uuid) {
        return blocksPlaced.getOrDefault(uuid, 0L);
    }

    /** Records one placed block for the player and marks the file for saving. */
    public void addBlockPlaced(final UUID uuid, final String name) {
        blocksPlaced.merge(uuid, 1L, Long::sum);
        if (name != null && !name.isBlank()) {
            names.put(uuid, name);
        }
        dirty = true;
        knownNamesCache = null;
    }

    public boolean dirty() {
        return dirty;
    }

    /** Writes the counters when anything changed. Failures are reported, never thrown. */
    public void save(final Consumer<String> warn) {
        if (!dirty) {
            return;
        }

        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Sweats statistics that vanilla Minecraft does not track.",
                "blocks-placed is counted from the moment this plugin version was installed."));

        final List<Map.Entry<UUID, Long>> entries = new ArrayList<>(blocksPlaced.entrySet());
        entries.sort(Comparator.comparing(
                entry -> names.getOrDefault(entry.getKey(), entry.getKey().toString()),
                String.CASE_INSENSITIVE_ORDER));

        for (final Map.Entry<UUID, Long> entry : entries) {
            final UUID uuid = entry.getKey();
            config.set("players." + uuid + ".name", names.getOrDefault(uuid, uuid.toString()));
            config.set("players." + uuid + ".blocks-placed", entry.getValue());
        }

        try {
            final Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeAtomically(config);
            dirty = false;
        } catch (final IOException exception) {
            warn.accept("Could not save " + file + ": " + exception.getMessage());
        }
    }

    /** Player names as stored, useful for logs and tab completion of players seen before. */
    public List<String> knownNames() {
        List<String> cached = knownNamesCache;
        if (cached == null) {
            cached = names.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            knownNamesCache = cached;
        }
        return cached;
    }

    private void writeAtomically(final YamlConfiguration config) throws IOException {
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            config.save(temp.toFile());
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException exception) {
            try {
                Files.deleteIfExists(temp);
            } catch (final IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    private void quarantine(final Consumer<String> warn, final String reason) {
        final Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            warn.accept("Could not read " + file + " (" + reason + "); kept a copy as "
                    + backup.getFileName());
        } catch (final IOException exception) {
            warn.accept("Could not read " + file + " (" + reason + "); backup failed: "
                    + exception.getMessage());
        }
    }

    private static boolean hasContent(final String text) {
        return text.lines().anyMatch(line -> {
            final String body = line.strip();
            return !body.isEmpty() && !body.startsWith("#");
        });
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }
}
