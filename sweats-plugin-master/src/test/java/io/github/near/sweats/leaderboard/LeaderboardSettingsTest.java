package io.github.near.sweats.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LeaderboardSettingsTest {

    @Test
    void usesDocumentedDefaultsWhenNothingIsConfigured() {
        assertEquals(
                new LeaderboardSettings(
                        true,
                        LeaderboardSettings.DEFAULT_VIEW_DISTANCE,
                        LeaderboardSettings.DEFAULT_REFRESH_SECONDS,
                        LeaderboardSettings.DEFAULT_RANKING_REFRESH_SECONDS),
                LeaderboardSettings.from(new YamlConfiguration()));
    }

    @Test
    void readsTheConfiguredValues() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("leaderboard.enabled", false);
        config.set("leaderboard.view-distance", 24.0D);
        config.set("leaderboard.refresh-seconds", 5);
        config.set("leaderboard.ranking-refresh-seconds", 120);

        assertEquals(
                new LeaderboardSettings(false, 24.0D, 5, 120),
                LeaderboardSettings.from(config));
    }

    @Test
    void clampsRefreshIntervalsAndFallsBackForANonPositiveDistance() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("leaderboard.view-distance", -10.0D);
        config.set("leaderboard.refresh-seconds", 0);
        config.set("leaderboard.ranking-refresh-seconds", 1);

        final LeaderboardSettings settings = LeaderboardSettings.from(config);

        assertEquals(LeaderboardSettings.DEFAULT_VIEW_DISTANCE, settings.viewDistance());
        assertEquals(1, settings.refreshSeconds());
        assertEquals(5, settings.rankingRefreshSeconds());
        assertTrue(settings.enabled());
    }

    @Test
    void enabledCanBeTurnedOff() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("leaderboard.enabled", false);

        assertFalse(LeaderboardSettings.from(config).enabled());
    }
}
