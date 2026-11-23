package io.github.near.sweats.ping;

import org.bukkit.configuration.file.FileConfiguration;

public record PingSettings(int goodMaxMillis, int moderateMaxMillis) {

    public static final int DEFAULT_GOOD_MAX_MILLIS = 100;
    public static final int DEFAULT_MODERATE_MAX_MILLIS = 200;
    private static final int MAX_THRESHOLD_MILLIS = 60_000;

    public static PingSettings from(final FileConfiguration config) {
        return of(
                integer(config, "ping.good-max-ms", DEFAULT_GOOD_MAX_MILLIS),
                integer(config, "ping.moderate-max-ms", DEFAULT_MODERATE_MAX_MILLIS));
    }

    static PingSettings of(final int good, final int moderate) {
        if (good < 0 || good >= MAX_THRESHOLD_MILLIS) {
            return new PingSettings(DEFAULT_GOOD_MAX_MILLIS, DEFAULT_MODERATE_MAX_MILLIS);
        }
        if (moderate <= good || moderate > MAX_THRESHOLD_MILLIS) {
            return new PingSettings(good, Math.max(DEFAULT_MODERATE_MAX_MILLIS, good + 1));
        }
        return new PingSettings(good, moderate);
    }

    private static int integer(final FileConfiguration config, final String path, final int fallback) {
        final Object raw = config.get(path);
        return raw instanceof final Number number ? number.intValue() : fallback;
    }
}