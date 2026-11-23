package io.github.near.sweats.show;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml, read once on startup and on {@code /sweats reload}.
 */
public record ShowSettings(
        String format,
        String emptyMessage,
        String cooldownMessage,
        double radius,
        long cooldownMillis) {

    public static final String DEFAULT_FORMAT =
            "<dark_gray>»</dark_gray> <white><player></white> <gray>showed their <slot>:</gray> <item>";

    public static ShowSettings from(final FileConfiguration config) {
        return new ShowSettings(
                text(config, "format", DEFAULT_FORMAT),
                text(config, "messages.empty", "<gray>Your <slot> slot is empty.</gray>"),
                text(config, "messages.cooldown", "<red>Slow down!</red> <gray>Try again in <seconds>s.</gray>"),
                Math.max(0.0D, config.getDouble("radius", 100_000.0D)),
                Math.max(0L, Math.round(config.getDouble("cooldown-seconds", 1.5D) * 1000.0D)));
    }

    private static String text(final FileConfiguration config, final String path, final String fallback) {
        final String value = config.getString(path);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
