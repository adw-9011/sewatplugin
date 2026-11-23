package io.github.near.sweats.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.near.sweats.TestCommandSenders;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class LeaderboardLayoutTest {

    private static PlaytimeEntry entry(final String name, final long ticks) {
        return new PlaytimeEntry(
                UUID.nameUUIDFromBytes(name.getBytes()), name, ticks);
    }

    @Test
    void showsTheViewerFirstThenTheTopTen() {
        final List<PlaytimeEntry> ranking = List.of(
                entry("Alex", 72_000L),
                entry("Sam", 36_000L));

        final Component display = LeaderboardLayout.display(144_000L, ranking);

        assertEquals(
                "Your playtime: 2h 0m\n"
                        + "\n"
                        + "#1 Alex - 1h 0m\n"
                        + "#2 Sam - 30m",
                TestCommandSenders.plain(display));
    }

    @Test
    void numbersTheRowsAndStopsAtTen() {
        final List<PlaytimeEntry> ranking = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            ranking.add(entry("Player" + index, 72_000L - index * 1_200L));
        }

        final List<Component> lines = LeaderboardLayout.lines(0L, ranking);

        assertEquals(LeaderboardLayout.TOP_SIZE + 2, lines.size());
        assertEquals("Your playtime: 0m", TestCommandSenders.plain(lines.get(0)));
        assertEquals("#1 Player0 - 1h 0m", TestCommandSenders.plain(lines.get(2)));
        assertEquals("#10 Player9 - 51m", TestCommandSenders.plain(lines.get(11)));
    }

    @Test
    void anEmptyRankingStillShowsTheViewerLine() {
        final List<Component> lines = LeaderboardLayout.lines(1_200L, List.of());

        assertEquals(1, lines.size());
        assertEquals("Your playtime: 1m", TestCommandSenders.plain(lines.getFirst()));
    }
}
