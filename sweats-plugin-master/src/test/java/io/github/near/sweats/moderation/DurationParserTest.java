package io.github.near.sweats.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DurationParserTest {

    @Test
    void parsesEveryDocumentedUnit() {
        assertEquals(30_000L, DurationParser.parse("30s").millis());
        assertEquals(600_000L, DurationParser.parse("10m").millis());
        assertEquals(7_200_000L, DurationParser.parse("2h").millis());
        assertEquals(604_800_000L, DurationParser.parse("7d").millis());
        assertEquals(1_209_600_000L, DurationParser.parse("2w").millis());
        assertEquals(DurationParser.MONTH_MILLIS, DurationParser.parse("1mo").millis());
        assertEquals(30L * 24L * 60L * 60L * 1000L, DurationParser.MONTH_MILLIS);
    }

    @Test
    void acceptsCaseInsensitivePermanent() {
        assertTrue(DurationParser.parse("permanent").permanent());
        assertTrue(DurationParser.parse(" PERMANENT ").permanent());
        assertFalse(DurationParser.parse("2H").permanent());
    }

    @Test
    void rejectsZeroNegativeMalformedAndOverflowingDurations() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("0s"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("-1m"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("1y"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("1.5h"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("mo"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(null));
        assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parse("999999999999999999999999d"));
    }
}
