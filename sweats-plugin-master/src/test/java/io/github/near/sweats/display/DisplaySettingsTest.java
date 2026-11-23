package io.github.near.sweats.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DisplaySettingsTest {

    @Test
    void usesVisibleDonutSidebarDefaultsAndExistingServerAddress() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("server.address", "play.example.net");

        final DisplaySettings settings = DisplaySettings.from(config);
        assertTrue(settings.scoreboardEnabled());
        assertTrue(settings.defaultVisible());
        assertTrue(settings.tablistEnabled());
        assertEquals("&6&lDONUT SMP", settings.title());
        assertEquals("play.example.net", settings.serverAddress());
        assertEquals(DisplaySettings.DEFAULT_LINES, settings.lines());
        assertEquals(20L, settings.refreshTicks());
    }

    @Test
    void readsConfiguredSwitchesTitleAddressLinesAndRefreshInterval() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("scoreboard.enabled", false);
        config.set("scoreboard.default-visible", false);
        config.set("scoreboard.title", "&bTest");
        config.set("scoreboard.server-address", "sidebar.example.net");
        config.set("scoreboard.lines", java.util.List.of("&7Online: %online%"));
        config.set("scoreboard.refresh-interval", 40);
        config.set("tablist.enabled", false);
        config.set("server.name", "CREATIVE");
        config.set("server.address", "play.example.net");

        final DisplaySettings settings = DisplaySettings.from(config);
        assertFalse(settings.scoreboardEnabled());
        assertFalse(settings.defaultVisible());
        assertFalse(settings.tablistEnabled());
        assertEquals("CREATIVE", settings.serverName());
        assertEquals("sidebar.example.net", settings.serverAddress());
        assertEquals("&bTest", settings.title());
        assertEquals(java.util.List.of("&7Online: %online%"), settings.lines());
        assertEquals(40L, settings.refreshTicks());
    }

    @Test
    void clampsRefreshRateAndLimitsSidebarLines() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("scoreboard.refresh-interval", 0);
        config.set("scoreboard.lines", java.util.Collections.nCopies(20, "line"));

        final DisplaySettings settings = DisplaySettings.from(config);
        assertEquals(20L, settings.refreshTicks());
        assertEquals(DisplaySettings.MAX_LINES, settings.lines().size());
    }

    @Test
    void emptyAddressOverrideFallsBackToLegacyServerAddress() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("scoreboard.server-address", " ");
        config.set("server.address", "play.example.net");

        assertEquals("play.example.net", DisplaySettings.from(config).serverAddress());
    }
}
