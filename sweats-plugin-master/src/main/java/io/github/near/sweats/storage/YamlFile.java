package io.github.near.sweats.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * A YAML file that is read once and written atomically.
 *
 * <p>A file that cannot be parsed is copied aside before the caller starts from defaults, so a
 * broken edit never destroys the original data.
 */
public final class YamlFile {

    private final Path file;

    public YamlFile(final Path file) {
        this.file = file;
    }

    public Path path() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    public YamlConfiguration load(final Consumer<String> warn) {
        if (!exists()) {
            return null;
        }

        final String text;
        try {
            text = Files.readString(file);
        } catch (final IOException exception) {
            warn.accept("Could not read " + file + ": " + exception.getMessage());
            return null;
        }

        final YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (final InvalidConfigurationException | RuntimeException exception) {
            quarantine(warn, exception.getMessage());
            return null;
        }
        return config;
    }

    public boolean save(final YamlConfiguration config, final Consumer<String> warn) {
        try {
            final Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeAtomically(config);
            return true;
        } catch (final IOException | RuntimeException exception) {
            warn.accept("Could not save " + file + ": " + exception.getMessage());
            return false;
        }
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
        } catch (final IOException | RuntimeException exception) {
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
}
