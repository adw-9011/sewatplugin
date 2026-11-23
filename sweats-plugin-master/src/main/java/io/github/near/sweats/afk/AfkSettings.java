package io.github.near.sweats.afk;

import org.bukkit.configuration.file.FileConfiguration;

/** Settings for AFK tracking, read from the {@code afk} config section. */
public record AfkSettings(boolean enabled, int timeoutSeconds, int checkSeconds) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_CHECK_SECONDS = 1;

    public static AfkSettings from(final FileConfiguration config) {
        return new AfkSettings(
                config.getBoolean("afk.enabled", true),
                Math.max(30, config.getInt("afk.timeout-seconds", DEFAULT_TIMEOUT_SECONDS)),
                Math.max(1, config.getInt("afk.check-seconds", DEFAULT_CHECK_SECONDS)));
    }

    long timeoutMillis() {
        return timeoutSeconds * 1000L;
    }

    long checkTicks() {
        return checkSeconds * 20L;
    }
}
