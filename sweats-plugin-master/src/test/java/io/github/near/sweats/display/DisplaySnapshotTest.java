package io.github.near.sweats.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.stats.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class DisplaySnapshotTest {

    private static final long MINUTE = 20L * 60L;
    private static final Rank MEMBER = new Rank("Member", Component.text("Member", NamedTextColor.GRAY), 0, true);

    @Test
    void formatsCurrenciesPlaytimeAndOnlineAgainstMaxCapacity() {
        final DisplaySnapshot snapshot = DisplaySnapshot.of(
                new PlayerProfile(MINUTE * 30L + 59L * 20L, 0, 0),
                MEMBER,
                "None",
                12_500L,
                250L,
                true,
                15,
                100);

        assertEquals(MEMBER, snapshot.rank());
        assertEquals("None", snapshot.team());
        assertEquals("12,500", snapshot.coin());
        assertEquals("250", snapshot.shards());
        assertEquals("30m", snapshot.playtime());
        assertEquals("15/100", snapshot.online());
    }

    @Test
    void marksUnloadedBalancesInsteadOfDisplayingFakeZeros() {
        final DisplaySnapshot snapshot = DisplaySnapshot.of(
                new PlayerProfile(0L, 0, 0), MEMBER, "None", 0L, 0L, false, 0, 20);

        assertEquals("…", snapshot.coin());
        assertEquals("…", snapshot.shards());
        assertEquals("0m", snapshot.playtime());
        assertEquals("0/20", snapshot.online());
    }
}
