package io.github.near.sweats.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LeaderboardCategoryTest {

    @Test
    void parsesKeysAndEnumNames() {
        assertEquals(LeaderboardCategory.PLAYTIME, LeaderboardCategory.fromKey("playtime"));
        assertEquals(LeaderboardCategory.STREAK, LeaderboardCategory.fromKey(" streak "));
        assertEquals(LeaderboardCategory.DEATHS, LeaderboardCategory.fromKey("DEATHS"));
        assertEquals(LeaderboardCategory.AFK, LeaderboardCategory.fromKey("afk"));
        assertNull(LeaderboardCategory.fromKey("nope"));
        assertNull(LeaderboardCategory.fromKey(""));
    }

    @Test
    void everyCategoryHasItsOwnTheme() {
        assertEquals(6, LeaderboardCategory.values().length);
        final Set<String> themes = new HashSet<>();
        final Set<String> pedestals = new HashSet<>();
        final Set<String> icons = new HashSet<>();
        final Set<String> colors = new HashSet<>();
        final Set<String> symbols = new HashSet<>();
        for (final LeaderboardCategory category : LeaderboardCategory.values()) {
            assertTrue(category.key().equals(category.key().toLowerCase(Locale.ROOT)));
            assertNotNull(category.color());
            assertTrue(category.symbol().length() > 0);
            assertNotNull(category.pedestal().name());
            assertNotNull(category.icon().name());
            assertNotNull(category.format(1_200L));

            // Material#isBlock/#isItem need a running server's registry, so compare the theme
            // constants by name here and leave their runtime validity to the plugin itself.
            assertTrue(pedestals.add(category.pedestal().name()),
                    category + " pedestal must be unique");
            assertTrue(icons.add(category.icon().name()), category + " icon must be unique");
            assertTrue(colors.add(category.color().toString()),
                    category + " colour must be unique");
            assertTrue(symbols.add(category.symbol()), category + " symbol must be unique");
            assertTrue(themes.add(category.key()), category + " key must be unique");
        }
        assertEquals(6, themes.size());
    }

    @Test
    void timeBasedCategoriesUseDurationsAndOthersUseCounts() {
        assertEquals("1h 0m", LeaderboardCategory.PLAYTIME.format(72_000L));
        assertEquals("5m", LeaderboardCategory.AFK.format(6_000L));
        assertEquals("1,234", LeaderboardCategory.DEATHS.format(1_234L));
        assertEquals("7", LeaderboardCategory.STREAK.format(7L));
    }
}
