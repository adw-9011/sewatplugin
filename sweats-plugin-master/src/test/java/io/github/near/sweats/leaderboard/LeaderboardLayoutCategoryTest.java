package io.github.near.sweats.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class LeaderboardLayoutCategoryTest {

    private static List<LeaderboardEntry> ranking(final long value) {
        return List.of(new LeaderboardEntry(
                UUID.nameUUIDFromBytes("Alex".getBytes()), "Alex", value));
    }

    @Test
    void eachCategoryGetsItsOwnHeadingAndValueFormat() {
        assertEquals("⚔ Kills ⚔\nYour kills: 12\n\n#1 Alex - 12",
                TestCommandSenders.plain(LeaderboardLayout.display(
                        LeaderboardCategory.KILLS, 12L, ranking(12L))));
        assertEquals("★ Total Votes ★\nYour votes: 1,200",
                TestCommandSenders.plain(LeaderboardLayout.display(
                        LeaderboardCategory.VOTES, 1_200L, List.of())));
        assertEquals("☠ Deaths ☠\nYour deaths: 3",
                TestCommandSenders.plain(LeaderboardLayout.display(
                        LeaderboardCategory.DEATHS, 3L, List.of())));
    }

    @Test
    void afkAndPlaytimeUseDurations() {
        final List<Component> lines = LeaderboardLayout.lines(
                LeaderboardCategory.AFK, 6_000L, ranking(3_600L));

        assertEquals("Your AFK time: 5m", TestCommandSenders.plain(lines.get(1)));
        assertEquals("#1 Alex - 3m", TestCommandSenders.plain(lines.get(3)));
    }

    @Test
    void streakCategoryUsesTheBestStreakWording() {
        final String text = TestCommandSenders.plain(LeaderboardLayout.display(
                LeaderboardCategory.STREAK, 4L, ranking(4L)));

        assertTrue(text.startsWith("✦ Vote Streak ✦\nYour best vote streak: 4"));
    }
}
