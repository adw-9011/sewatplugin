package io.github.near.sweats.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class StatsFormatTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Test
    void groupsLongCounts() {
        assertEquals("0", StatsFormat.count(0));
        assertEquals("999", StatsFormat.count(999));
        assertEquals("1,234,567", StatsFormat.count(1_234_567L));
    }

    @Test
    void turnsCentimetresIntoBlocksAndKilometres() {
        assertEquals("0 blocks", StatsFormat.distance(0));
        assertEquals("9 blocks", StatsFormat.distance(950));
        assertEquals("123 blocks", StatsFormat.distance(12_345));
        assertEquals("12,345 blocks (12.3 km)", StatsFormat.distance(1_234_500));
    }

    @Test
    void printsPlaytimeFromTicks() {
        assertEquals("0s", StatsFormat.duration(0));
        assertEquals("38s", StatsFormat.duration(20L * 38L));
        assertEquals("1m 1s", StatsFormat.duration(20L * 61L));
        assertEquals("4h 12m", StatsFormat.duration(20L * (4L * 3_600L + 12L * 60L)));
        assertEquals("3d 4h 12m", StatsFormat.duration(20L * (3L * 86_400L + 4L * 3_600L + 12L * 60L)));
    }

    @Test
    void neverPrintsNegativePlaytime() {
        assertEquals("0s", StatsFormat.duration(-1_000L));
        assertTrue(StatsFormat.duration(Long.MAX_VALUE / 4L).endsWith("m"));
    }

    @Test
    void printsCompactPlaytimeForTheScoreboard() {
        assertEquals("0m", StatsFormat.playtime(0L));
        assertEquals("<1m", StatsFormat.playtime(20L * 59L));
        assertEquals("1m", StatsFormat.playtime(20L * 60L));
        assertEquals("42m", StatsFormat.playtime(20L * (42L * 60L)));
        assertEquals("6h 42m", StatsFormat.playtime(20L * (6L * 3_600L + 42L * 60L)));
        assertEquals("2d 6h 42m", StatsFormat.playtime(20L * (2L * 86_400L + 6L * 3_600L + 42L * 60L)));
    }

    @Test
    void compactPlaytimeHandlesZeroNegativeAndLargeValues() {
        assertEquals("0m", StatsFormat.playtime(-1_000L));
        assertTrue(StatsFormat.playtime(Long.MAX_VALUE / 4L).endsWith("m"));
    }

    @Test
    void formatsADateInTheGivenZone() {
        final long millis = Instant.parse("2026-11-12T22:56:00Z").toEpochMilli();

        assertEquals("12 Nov 2026 at 22:56", StatsFormat.date(millis, ZoneId.of("UTC")));
        assertEquals("13 Nov 2026 at 04:26", StatsFormat.date(millis, ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void describesHowLongAgoSomethingHappened() {
        assertEquals("never", StatsFormat.relative(0L, NOW));
        assertEquals("today", StatsFormat.relative(NOW.minus(Duration.ofHours(1)).toEpochMilli(), NOW));
        assertEquals("1 day ago", StatsFormat.relative(NOW.minus(Duration.ofDays(1)).toEpochMilli(), NOW));
        assertEquals("3 days ago", StatsFormat.relative(NOW.minus(Duration.ofDays(3)).toEpochMilli(), NOW));
        assertEquals("45 days ago", StatsFormat.relative(NOW.minus(Duration.ofDays(45)).toEpochMilli(), NOW));
        assertEquals("2 months ago", StatsFormat.relative(NOW.minus(Duration.ofDays(75)).toEpochMilli(), NOW));
        assertEquals("2 years ago", StatsFormat.relative(NOW.minus(Duration.ofDays(800)).toEpochMilli(), NOW));
    }
}
