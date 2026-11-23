package io.github.near.sweats.ram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RamFormatTest {

    @Test
    void formatsMegabytesAndGigabytes() {
        assertEquals("0.0 MB", RamFormat.bytes(0L));
        assertEquals("512.0 MB", RamFormat.bytes(512L * 1024L * 1024L));
        assertEquals("1.00 GB", RamFormat.bytes(1024L * 1024L * 1024L));
        assertEquals("1.50 GB", RamFormat.bytes(1536L * 1024L * 1024L));
    }

    @Test
    void neverFormatsNegativeBytes() {
        assertEquals("0.0 MB", RamFormat.bytes(-1L));
    }

    @Test
    void formatsKnownAndUnknownPercentages() {
        assertEquals("25.0%", RamFormat.percent(RamStats.from(4_000L, 3_000L, 4_000L)));
        assertEquals("n/a", RamFormat.percent(RamStats.from(4_000L, 3_000L, 0L)));
    }
}