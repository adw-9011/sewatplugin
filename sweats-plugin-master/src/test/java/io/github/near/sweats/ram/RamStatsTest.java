package io.github.near.sweats.ram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RamStatsTest {

    @Test
    void reportsUsedAllocatedAndMaximum() {
        final RamStats stats = RamStats.from(1_000L, 400L, 2_000L);

        assertEquals(600L, stats.usedBytes());
        assertEquals(1_000L, stats.allocatedBytes());
        assertEquals(2_000L, stats.maxBytes());
        assertTrue(stats.hasMaximum());
        assertEquals(30.0D, stats.usedPercent(), 0.0001D);
    }

    @Test
    void clampsImpossibleValuesInsteadOfThrowing() {
        final RamStats stats = RamStats.from(-10L, 5L, -1L);

        assertEquals(0L, stats.usedBytes());
        assertEquals(0L, stats.allocatedBytes());
        assertEquals(0L, stats.maxBytes());
        assertFalse(stats.hasMaximum());
        assertEquals(0.0D, stats.usedPercent(), 0.0001D);
    }

    @Test
    void treatsFreeMemoryAboveTotalAsZeroUsage() {
        final RamStats stats = RamStats.from(1_000L, 2_000L, 4_000L);

        assertEquals(0L, stats.usedBytes());
        assertEquals(1_000L, stats.allocatedBytes());
    }

    @Test
    void treatsMissingOrUnlimitedMaximumAsUnknown() {
        assertFalse(RamStats.from(1_000L, 500L, 0L).hasMaximum());
        assertFalse(RamStats.from(1_000L, 500L, Long.MAX_VALUE).hasMaximum());
    }

    @Test
    void capsTheReportedPercentageAtOneHundred() {
        final RamStats stats = RamStats.from(2_000L, 0L, 1_000L);

        assertEquals(100.0D, stats.usedPercent(), 0.0001D);
    }
}