package io.github.near.sweats.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.rank.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class RankChatListenerTest {

    @Test
    void formatsRankedPlayersWithTheirRankColorAndAWhiteMessage() {
        final Rank admin = new Rank("Admin", Component.text("Admin", NamedTextColor.RED), 40, true);

        final Component formatted = RankChatListener.format(
                admin, "Suhaan", Component.text("Hello!", NamedTextColor.YELLOW));

        assertEquals("[Admin] Suhaan: Hello!", TestCommandSenders.plain(formatted));
        assertEquals(NamedTextColor.RED, TestCommandSenders.colorOf(formatted, "Admin"));
        assertEquals(NamedTextColor.RED, TestCommandSenders.colorOf(formatted, "Suhaan"));
        assertEquals(NamedTextColor.WHITE, TestCommandSenders.colorOf(formatted, "Hello!"));
    }

    @Test
    void defaultPlayersChatWithoutATagInGrey() {
        final Rank defaultRank = new Rank("Default", Component.text("Default", NamedTextColor.GRAY), 0, true);

        final Component formatted = RankChatListener.format(
                defaultRank, "Suhaan", Component.text("Hello!"));

        assertEquals("Suhaan: Hello!", TestCommandSenders.plain(formatted));
        assertEquals(NamedTextColor.GRAY, TestCommandSenders.colorOf(formatted, "Suhaan"));
        assertEquals(NamedTextColor.GRAY, TestCommandSenders.colorOf(formatted, "Hello!"));
    }

    @Test
    void grinderAndAboveChatInWhite() {
        final Rank grinder = new Rank("Grinder", Component.text("Grinder", NamedTextColor.GREEN), 10, true);

        final Component formatted = RankChatListener.format(
                grinder, "Suhaan", Component.text("Hello!", NamedTextColor.YELLOW));

        assertEquals("[Grinder] Suhaan: Hello!", TestCommandSenders.plain(formatted));
        assertEquals(NamedTextColor.WHITE, TestCommandSenders.colorOf(formatted, "Hello!"));
    }

    @Test
    void plusColourOnlyChangesThePlusIconOfSweatPlus() {
        final Rank sweatPlus = new Rank("Sweat+", Component.text("Sweat+", NamedTextColor.LIGHT_PURPLE), 30, true);

        final Component formatted = RankChatListener.format(
                sweatPlus,
                "Suhaan",
                Component.text("Hello!", NamedTextColor.YELLOW),
                NamedTextColor.AQUA,
                true);

        assertEquals("[Sweat+] Suhaan: Hello!", TestCommandSenders.plain(formatted));
        assertEquals(NamedTextColor.AQUA, TestCommandSenders.colorOf(formatted, "+"));
        assertEquals(
                NamedTextColor.LIGHT_PURPLE, TestCommandSenders.colorOf(formatted, "Sweat"));
        assertEquals(NamedTextColor.WHITE, TestCommandSenders.colorOf(formatted, "Hello!"));
    }

    @Test
    void memberChannelWrapsTheRankedMessage() {
        final Rank sweat = new Rank("Sweat", Component.text("Sweat", NamedTextColor.LIGHT_PURPLE), 20, true);
        final Component ranked = RankChatListener.format(
                sweat, "Suhaan", Component.text("Hi"), null, true);

        final Component channel =
                ChatFormat.channel("MEMBER", NamedTextColor.LIGHT_PURPLE, ranked);

        assertEquals("[MEMBER] [Sweat] Suhaan: Hi", TestCommandSenders.plain(channel));
        assertEquals(
                NamedTextColor.LIGHT_PURPLE,
                TestCommandSenders.colorOf(channel, "[MEMBER] "));
    }
}