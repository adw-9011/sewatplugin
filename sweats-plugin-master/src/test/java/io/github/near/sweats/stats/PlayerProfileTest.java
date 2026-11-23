package io.github.near.sweats.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlayerProfileTest {

    @Test
    void exposesPlayerKillsAndDeaths() {
        final PlayerProfile profile = new PlayerProfile(0L, 100, 32);

        assertEquals(100, profile.kills());
        assertEquals(32, profile.deaths());
    }
}
