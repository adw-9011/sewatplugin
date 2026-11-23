package io.github.near.sweats.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PingSettingsTest {

    @Test
    void readsConfiguredThresholds() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("ping.good-max-ms", 150);
        config.set("ping.moderate-max-ms", 250);

        assertEquals(new PingSettings(150, 250), PingSettings.from(config));
    }

    @Test
    void usesDefaultsWhenThresholdsAreMissingOrNotNumbers() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("ping.good-max-ms", "fast");
        config.set("ping.moderate-max-ms", true);

        assertEquals(new PingSettings(100, 200), PingSettings.from(config));
    }

    @Test
    void repairsNegativeOutOfOrderAndExcessiveThresholds() {
        assertEquals(new PingSettings(100, 200), PingSettings.of(-1, 50));
        assertEquals(new PingSettings(300, 301), PingSettings.of(300, 200));
        assertEquals(new PingSettings(100, 200), PingSettings.of(60_000, 1));
        assertEquals(new PingSettings(100, 200), PingSettings.of(60_001, 300));
    }

    @Test
    void keepsValidCustomThresholds() {
        assertEquals(new PingSettings(50, 150), PingSettings.of(50, 150));
    }
}