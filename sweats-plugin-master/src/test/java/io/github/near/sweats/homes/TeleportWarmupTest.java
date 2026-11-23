package io.github.near.sweats.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TeleportWarmupTest {

    private static final long START = 1_000_000L;

    @Test
    void tinyPositionChangesDoNotCancelTheWarmup() {
        final TeleportWarmup warmup = new TeleportWarmup("world", 10, 64, -10, START, 5_000L);

        assertFalse(warmup.moved("world", 10.1, 64.1, -10.1));
        assertTrue(warmup.moved("world", 10.3, 64, -10));
    }

    @Test
    void changingWorldAlwaysCancels() {
        final TeleportWarmup warmup = new TeleportWarmup("world", 10, 64, -10, START, 5_000L);

        assertTrue(warmup.moved("world_nether", 10, 64, -10));
        assertTrue(warmup.moved(null, 10, 64, -10));
    }

    @Test
    void aNullStartWorldOnlyMatchesANullCurrentWorld() {
        final TeleportWarmup warmup = new TeleportWarmup(null, 0, 64, 0, START, 5_000L);

        assertFalse(warmup.moved(null, 0, 64, 0));
        assertTrue(warmup.moved("world", 0, 64, 0));
    }

    @Test
    void countsDownWholeSecondsRoundedUp() {
        final TeleportWarmup warmup = new TeleportWarmup("world", 0, 64, 0, START, 5_000L);

        assertEquals(5, warmup.secondsRemaining(START));
        assertEquals(5, warmup.secondsRemaining(START + 1));
        assertEquals(4, warmup.secondsRemaining(START + 1_001));
        assertEquals(1, warmup.secondsRemaining(START + 4_001));
        assertEquals(0, warmup.secondsRemaining(START + 5_000));
    }

    @Test
    void finishesExactlyAtTheDeadlineAndNeverBefore() {
        final TeleportWarmup warmup = new TeleportWarmup("world", 0, 64, 0, START, 5_000L);

        assertFalse(warmup.finished(START + 4_999));
        assertTrue(warmup.finished(START + 5_000));
    }

    @Test
    void zeroDurationIsImmediatelyFinished() {
        final TeleportWarmup warmup = new TeleportWarmup("world", 0, 64, 0, START, -50L);

        assertTrue(warmup.finished(START));
        assertEquals(0, warmup.secondsRemaining(START));
    }
}
