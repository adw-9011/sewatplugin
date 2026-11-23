package io.github.near.sweats.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class RankTest {

    private static final Rank ADMIN = new Rank("Admin", Component.text("Admin", NamedTextColor.RED), 40, true);
    private static final Rank SWEAT = new Rank("Sweat", Component.text("Sweat", NamedTextColor.LIGHT_PURPLE), 20, true);
    private static final Rank ALPHA = new Rank("Alpha", Component.text("Alpha", NamedTextColor.BLUE), 20, true);
    private static final Rank ZETA = new Rank("Zeta", Component.text("Zeta", NamedTextColor.BLUE), 20, true);
    private static final Rank DEFAULT = new Rank("Default", Component.text("Default", NamedTextColor.GRAY), 0, true);
    private static final Rank SWEAT_PLUS =
            new Rank("Sweat+", Component.text("Sweat+", NamedTextColor.LIGHT_PURPLE), 30, true);

    @Test
    void sortsHigherWeightsFirstAndEqualWeightsByName() {
        assertTrue(ADMIN.compareTo(SWEAT) < 0);
        assertTrue(SWEAT.compareTo(ADMIN) > 0);
        assertTrue(ALPHA.compareTo(ZETA) < 0);
        assertTrue(ZETA.compareTo(ALPHA) > 0);
    }

    @Test
    void formatsThePrefixAndTabListName() {
        assertEquals("[Admin] ", TestCommandSenders.plain(ADMIN.prefix()));
        assertEquals("[Admin] Suhaan", TestCommandSenders.plain(ADMIN.tabListName("Suhaan")));
        assertEquals("[Admin]", TestCommandSenders.plain(ADMIN.label()));
        assertEquals(NamedTextColor.RED, TestCommandSenders.colorOf(ADMIN.tabListName("Suhaan"), "Suhaan"));
    }

    @Test
    void defaultRankHasNoVisibleTag() {
        assertEquals("", TestCommandSenders.plain(DEFAULT.displayPrefix()));
        assertEquals("Suhaan", TestCommandSenders.plain(DEFAULT.tabListName("Suhaan")));
        assertEquals(NamedTextColor.GRAY, TestCommandSenders.colorOf(DEFAULT.tabListName("Suhaan"), "Suhaan"));
        assertEquals("[Default]", TestCommandSenders.plain(DEFAULT.label()));
    }

    @Test
    void aPlusColourOnlyRecoloursThePlusOfSweatPlus() {
        final var label = SWEAT_PLUS.label(NamedTextColor.AQUA);

        assertEquals("[Sweat+]", TestCommandSenders.plain(label));
        assertEquals(NamedTextColor.AQUA, TestCommandSenders.colorOf(label, "+"));
        assertEquals(
                NamedTextColor.LIGHT_PURPLE, TestCommandSenders.colorOf(label, "Sweat"));

        final var tab = SWEAT_PLUS.tabListName("Alex", NamedTextColor.GOLD);
        assertEquals("[Sweat+] Alex", TestCommandSenders.plain(tab));
        assertEquals(NamedTextColor.GOLD, TestCommandSenders.colorOf(tab, "+"));
        assertEquals(
                NamedTextColor.LIGHT_PURPLE, TestCommandSenders.colorOf(tab, "Alex"));
    }

    @Test
    void otherRanksIgnoreAPlusColour() {
        assertEquals(
                "[Sweat]", TestCommandSenders.plain(SWEAT.label(NamedTextColor.AQUA)));
        assertEquals(
                NamedTextColor.LIGHT_PURPLE,
                TestCommandSenders.colorOf(SWEAT.label(NamedTextColor.AQUA), "Sweat"));
        assertEquals(
                NamedTextColor.RED,
                TestCommandSenders.colorOf(ADMIN.label(NamedTextColor.AQUA), "Admin"));
        assertEquals(
                "[Sweat+]",
                TestCommandSenders.plain(SWEAT_PLUS.label(null)));
    }

    @Test
    void rankKeysAreCaseInsensitiveAndTreatUnderscoresAsSpaces() {
        assertEquals("community manager", Rank.key("  Community_Manager  "));
        assertEquals("sweat+", Rank.key("Sweat+"));
    }

    @Test
    void parsesNamedAndHexColours() {
        assertEquals(NamedTextColor.LIGHT_PURPLE, RankColor.parse("light_purple"));
        assertEquals(NamedTextColor.DARK_PURPLE, RankColor.parse("dark-purple"));
        assertEquals(TextColor.color(0x39FF14), RankColor.parse("#39FF14"));
        assertEquals(TextColor.color(0x39FF14), RankColor.parse("39FF14"));
        assertNull(RankColor.parse("not-a-colour"));
        assertNull(RankColor.parse(""));
        assertNull(RankColor.parse(null));
    }

    @Test
    void serializesColours() {
        assertEquals("light_purple", RankColor.serialize(NamedTextColor.LIGHT_PURPLE));
        assertEquals("#39FF14", RankColor.serialize(TextColor.color(0x39FF14)));
    }

    @Test
    void parsesLegacyFormattingCodes() {
        final Rank rank = new Rank("&c&lAdmin", RankFormat.parse("&c&lAdmin"), 40, true);
        final Component formatted = rank.label();
        assertEquals("[Admin]", TestCommandSenders.plain(formatted));
        assertEquals(NamedTextColor.RED, TestCommandSenders.colorOf(formatted, "Admin"));
        assertTrue(TestCommandSenders.hasDecoration(formatted, "Admin", net.kyori.adventure.text.format.TextDecoration.BOLD));
    }

    @Test
    void parsesMultipleFormattingCodes() {
        final Rank rank = new Rank("&6&l&nVIP&r&a+", RankFormat.parse("&6&l&nVIP&r&a+"), 25, true);
        final Component formatted = rank.label();
        assertEquals("[VIP+]", TestCommandSenders.plain(formatted));
        assertTrue(TestCommandSenders.hasDecoration(formatted, "VIP", net.kyori.adventure.text.format.TextDecoration.BOLD));
        assertTrue(TestCommandSenders.hasDecoration(formatted, "VIP", net.kyori.adventure.text.format.TextDecoration.UNDERLINED));
    }

    @Test
    void stripsCodesForKey() {
        assertEquals("admin", Rank.key("&c&lAdmin"));
        assertEquals("vip", Rank.key("&6&lVIP"));
        assertEquals("grinder", Rank.key("&aGrinder"));
    }
}