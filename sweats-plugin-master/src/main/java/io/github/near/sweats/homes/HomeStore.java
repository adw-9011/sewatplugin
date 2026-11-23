package io.github.near.sweats.homes;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persistent homes. Every slot is kept even when the owner's rank no longer unlocks it; the
 * service decides which slots are accessible.
 */
public final class HomeStore {

    private final YamlFile file;
    private final Map<UUID, Map<Integer, Home>> homes = new LinkedHashMap<>();

    public HomeStore(final Path file) {
        this.file = new YamlFile(file);
    }

    public void load(final Consumer<String> warn) {
        homes.clear();
        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            return;
        }
        final ConfigurationSection root = config.getConfigurationSection("homes");
        if (root == null) {
            return;
        }

        for (final String playerKey : root.getKeys(false)) {
            final UUID id;
            try {
                id = UUID.fromString(playerKey);
            } catch (final IllegalArgumentException exception) {
                warn.accept("Ignored homes for invalid UUID '" + playerKey + "'.");
                continue;
            }
            final ConfigurationSection section = root.getConfigurationSection(playerKey);
            if (section == null) {
                continue;
            }
            final Map<Integer, Home> playerHomes = new LinkedHashMap<>();
            for (final String slotKey : section.getKeys(false)) {
                final int slot;
                try {
                    slot = Integer.parseInt(slotKey);
                } catch (final NumberFormatException exception) {
                    warn.accept("Ignored home with invalid slot '" + slotKey + "' for " + playerKey + ".");
                    continue;
                }
                if (slot < 1) {
                    warn.accept("Ignored home slot " + slot + " for " + playerKey + "; slot must be positive.");
                    continue;
                }
                final ConfigurationSection home = section.getConfigurationSection(slotKey);
                if (home == null) {
                    continue;
                }
                final String world = home.getString("world");
                if (world == null || world.isBlank()) {
                    warn.accept("Ignored home slot " + slot + " for " + playerKey + ": no world.");
                    continue;
                }
                playerHomes.put(slot, new Home(
                        slot,
                        world,
                        home.getDouble("x"),
                        home.getDouble("y"),
                        home.getDouble("z"),
                        (float) home.getDouble("yaw"),
                        (float) home.getDouble("pitch")));
            }
            if (!playerHomes.isEmpty()) {
                homes.put(id, playerHomes);
            }
        }
    }

    public Map<Integer, Home> homes(final UUID id) {
        final Map<Integer, Home> stored = homes.get(id);
        return stored == null ? Map.of() : Map.copyOf(stored);
    }

    public Optional<Home> get(final UUID id, final int slot) {
        final Map<Integer, Home> stored = homes.get(id);
        return stored == null ? Optional.empty() : Optional.ofNullable(stored.get(slot));
    }

    /** Stores or replaces a home. The caller decided this was an explicit player action. */
    public boolean put(final UUID id, final Home home) {
        final Map<Integer, Home> playerHomes =
                homes.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
        return !home.equals(playerHomes.put(home.slot(), home));
    }

    public Optional<Home> remove(final UUID id, final int slot) {
        final Map<Integer, Home> playerHomes = homes.get(id);
        if (playerHomes == null) {
            return Optional.empty();
        }
        final Home removed = playerHomes.remove(slot);
        if (playerHomes.isEmpty()) {
            homes.remove(id);
        }
        return Optional.ofNullable(removed);
    }

    public boolean save(final Consumer<String> warn) {
        final YamlConfiguration config = new YamlConfiguration();
        homes.forEach((id, playerHomes) -> playerHomes.forEach((slot, home) -> {
            final String path = "homes." + id + "." + slot;
            config.set(path + ".world", home.world());
            config.set(path + ".x", home.x());
            config.set(path + ".y", home.y());
            config.set(path + ".z", home.z());
            config.set(path + ".yaw", home.yaw());
            config.set(path + ".pitch", home.pitch());
        }));
        return file.save(config, warn);
    }
}
