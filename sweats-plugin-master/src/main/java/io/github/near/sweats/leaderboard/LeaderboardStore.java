package io.github.near.sweats.leaderboard;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Stores where the op-placed playtime leaderboards live.
 *
 * <p>The text display entities themselves are not persisted - they are created for each viewer at
 * runtime. Only the block positions need to survive a restart.
 */
public final class LeaderboardStore {

    private final YamlFile file;
    private final Consumer<String> warn;
    private final Map<UUID, StoredDisplay> displays = new LinkedHashMap<>();

    public LeaderboardStore(final Path file, final Consumer<String> warn) {
        this.file = new YamlFile(file);
        this.warn = warn;
    }

    public void load() {
        displays.clear();

        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            return;
        }

        final ConfigurationSection section = config.getConfigurationSection("displays");
        if (section == null) {
            return;
        }

        for (final String key : section.getKeys(false)) {
            final UUID id = parseUuid(key);
            if (id == null) {
                continue;
            }

            final String world = section.getString(key + ".world");
            if (world == null || world.isBlank()) {
                continue;
            }

            final LeaderboardCategory category = LeaderboardCategory.fromKey(
                    section.getString(key + ".category", LeaderboardCategory.PLAYTIME.key()));
            displays.put(id, new StoredDisplay(
                    id,
                    world,
                    section.getDouble(key + ".x"),
                    section.getDouble(key + ".y"),
                    section.getDouble(key + ".z"),
                    category == null ? LeaderboardCategory.PLAYTIME : category));
        }
    }

    public List<StoredDisplay> all() {
        return List.copyOf(displays.values());
    }

    public StoredDisplay add(final Location location) {
        return add(location, LeaderboardCategory.PLAYTIME);
    }

    public StoredDisplay add(final Location location, final LeaderboardCategory category) {
        final World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("A leaderboard needs a world.");
        }

        final StoredDisplay stored = new StoredDisplay(
                UUID.randomUUID(),
                world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                category == null ? LeaderboardCategory.PLAYTIME : category);
        displays.put(stored.id(), stored);
        if (!save()) {
            displays.remove(stored.id());
            throw new IllegalStateException(
                    "Could not save leaderboards.yml; the board was not placed.");
        }
        return stored;
    }

    public boolean remove(final UUID id) {
        final StoredDisplay removed = displays.remove(id);
        if (removed == null) {
            return false;
        }
        if (!save()) {
            displays.put(id, removed);
            throw new IllegalStateException(
                    "Could not save leaderboards.yml; the board was not removed.");
        }
        return true;
    }

    /** The placed display closest to a point, if one is within {@code radius} blocks. */
    public Optional<StoredDisplay> nearest(final Location location, final double radius) {
        if (location.getWorld() == null) {
            return Optional.empty();
        }

        final String world = location.getWorld().getName();
        double closest = radius * radius;
        StoredDisplay found = null;
        for (final StoredDisplay display : displays.values()) {
            if (!display.world().equals(world)) {
                continue;
            }
            final double dx = display.x() - location.getX();
            final double dy = display.y() - location.getY();
            final double dz = display.z() - location.getZ();
            final double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= closest) {
                closest = distance;
                found = display;
            }
        }
        return Optional.ofNullable(found);
    }

    private boolean save() {
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Leaderboard displays placed with /leaderboard place <category>.",
                "Only positions and categories are stored; display entities are recreated on startup."));

        for (final StoredDisplay display : displays.values()) {
            final String path = "displays." + display.id();
            config.set(path + ".world", display.world());
            config.set(path + ".x", display.x());
            config.set(path + ".y", display.y());
            config.set(path + ".z", display.z());
            config.set(path + ".category", display.category().key());
        }

        return file.save(config, warn);
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    /** A saved leaderboard position. The world may not be loaded when this is read. */
    public record StoredDisplay(
            UUID id, String world, double x, double y, double z, LeaderboardCategory category) {

        /** Legacy constructor for boards saved before categories existed. */
        public StoredDisplay(final UUID id, final String world,
                final double x, final double y, final double z) {
            this(id, world, x, y, z, LeaderboardCategory.PLAYTIME);
        }

        /** The live location, or {@code null} when that world is not loaded right now. */
        public Location location() {
            final World loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z);
        }
    }
}
