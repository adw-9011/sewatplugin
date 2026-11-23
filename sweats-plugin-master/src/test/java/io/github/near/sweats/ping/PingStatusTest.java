package io.github.near.sweats.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class PingStatusTest {

    private static final PingSettings SETTINGS = PingSettings.of(100, 200);

    @Test
    void classifiesTheConfiguredBoundaries() {
        assertEquals(PingStatus.GOOD, PingStatus.forPing(0, SETTINGS));
        assertEquals(PingStatus.GOOD, PingStatus.forPing(100, SETTINGS));
        assertEquals(PingStatus.MODERATE, PingStatus.forPing(101, SETTINGS));
        assertEquals(PingStatus.MODERATE, PingStatus.forPing(200, SETTINGS));
        assertEquals(PingStatus.HIGH, PingStatus.forPing(201, SETTINGS));
    }

    @Test
    void usesGreenYellowAndRed() {
        assertEquals(NamedTextColor.GREEN, PingStatus.GOOD.color());
        assertEquals(NamedTextColor.YELLOW, PingStatus.MODERATE.color());
        assertEquals(NamedTextColor.RED, PingStatus.HIGH.color());
    }

    @Test
    void respectsCustomThresholds() {
        final PingSettings custom = PingSettings.of(50, 150);

        assertEquals(PingStatus.GOOD, PingStatus.forPing(50, custom));
        assertEquals(PingStatus.MODERATE, PingStatus.forPing(51, custom));
        assertEquals(PingStatus.MODERATE, PingStatus.forPing(150, custom));
        assertEquals(PingStatus.HIGH, PingStatus.forPing(151, custom));
    }

    @Test
    void treatsNegativePingAsGood() {
        assertEquals(PingStatus.GOOD, PingStatus.forPing(-1, SETTINGS));
    }
}