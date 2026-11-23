package io.github.near.sweats.leaderboard;

import org.bukkit.configuration.file.FileConfiguration;

/** The tuning values read from {@code config.yml} for the playtime leaderboard. */
public record LeaderboardSettings(
        boolean enabled,
        double viewDistance,
        int refreshSeconds,
        int rankingRefreshSeconds) {

    public static final double DEFAULT_VIEW_DISTANCE = 48.0D;
    public static final int DEFAULT_REFRESH_SECONDS = 2;
    public static final int DEFAULT_RANKING_REFRESH_SECONDS = 60;

    public static LeaderboardSettings from(final FileConfiguration config) {
        return new LeaderboardSettings(
                config.getBoolean("leaderboard.enabled", true),
                positive(config.getDouble("leaderboard.view-distance", DEFAULT_VIEW_DISTANCE)),
                Math.max(1, config.getInt(
                        "leaderboard.refresh-seconds", DEFAULT_REFRESH_SECONDS)),
                Math.max(5, config.getInt(
                        "leaderboard.ranking-refresh-seconds",
                        DEFAULT_RANKING_REFRESH_SECONDS)));
    }

    public double viewDistanceSquared() {
        return viewDistance * viewDistance;
    }

    private static double positive(final double value) {
        return value > 0.0D ? value : DEFAULT_VIEW_DISTANCE;
    }
}
