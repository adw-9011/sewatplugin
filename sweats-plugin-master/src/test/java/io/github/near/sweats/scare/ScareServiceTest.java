package io.github.near.sweats.scare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Covers the timing math behind {@link ScareService} that does not need a live server. */
class ScareServiceTest {

    @Test
    void cooldownIsFullRightAfterTheScare() {
        // Captured at the same instant as the scare, so the whole window is still left.
        assertEquals(3_000L, ScareService.remainingCooldown(1_000L, 1_000L, 3_000L));
    }

    @Test
    void cooldownCountsDownAndNeverGoesNegative() {
        // 1s into a 3s cooldown.
        assertEquals(2_000L, ScareService.remainingCooldown(1_000L, 2_000L, 3_000L));
        // Exactly at the boundary.
        assertEquals(0L, ScareService.remainingCooldown(1_000L, 4_000L, 3_000L));
        // Long after the boundary.
        assertEquals(0L, ScareService.remainingCooldown(1_000L, 99_000L, 3_000L));
    }

    @Test
    void aZeroCooldownNeverBlocks() {
        assertEquals(0L, ScareService.remainingCooldown(1_000L, 1_000L, 0L));
    }

    @Test
    void titleStaysForTheScareMinusTheFadeOut() {
        // 60 ticks = 3000 ms, minus a 250 ms fade.
        assertEquals(Duration.ofMillis(2_750L), ScareService.stayDuration(60));
        assertEquals(Duration.ofMillis(1_250L), ScareService.stayDuration(30));
    }

    @Test
    void veryShortScaresNeverProduceANegativeTitleTime() {
        assertEquals(Duration.ZERO, ScareService.stayDuration(0));
        assertEquals(Duration.ZERO, ScareService.stayDuration(1));
    }
}
