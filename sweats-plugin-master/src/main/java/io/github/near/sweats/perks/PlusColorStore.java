package io.github.near.sweats.perks;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Stores the selected {@code +} colour per player. The selection survives rank changes. */
final class PlusColorStore {

    private final YamlFile file;
    private final Map<UUID, String> colors = new LinkedHashMap<>();

    PlusColorStore(final Path file) {
        this.file = new YamlFile(file);
    }

    void load(final Consumer<String> warn) {
        colors.clear();
        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            return;
        }

        final ConfigurationSection section = config.getConfigurationSection("colors");
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(false)) {
            final UUID id;
            try {
                id = UUID.fromString(key);
            } catch (final IllegalArgumentException exception) {
                warn.accept("Ignored plus colour for invalid UUID '" + key + "'.");
                continue;
            }
            final String name = section.getString(key);
            if (name == null || color(name) == null) {
                warn.accept("Ignored invalid plus colour '" + name + "' for " + key + ".");
                continue;
            }
            colors.put(id, name.toLowerCase(Locale.ROOT));
        }
    }

    Optional<NamedTextColor> color(final UUID id) {
        final String name = colors.get(id);
        return name == null ? Optional.empty() : Optional.ofNullable(color(name));
    }

    Optional<String> name(final UUID id) {
        return Optional.ofNullable(colors.get(id));
    }

    boolean put(final UUID id, final String name) {
        return !name.equals(colors.put(id, name));
    }

    boolean remove(final UUID id) {
        return colors.remove(id) != null;
    }

    boolean save(final Consumer<String> warn) {
        final YamlConfiguration config = new YamlConfiguration();
        colors.forEach((id, name) -> config.set("colors." + id, name));
        return file.save(config, warn);
    }

    static NamedTextColor color(final String raw) {
        if (raw == null) {
            return null;
        }
        return NamedTextColor.NAMES.value(raw.trim().toLowerCase(Locale.ROOT));
    }
}
