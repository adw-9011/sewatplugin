package io.github.near.sweats.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.stats.PlayerProfile;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DisplayLayoutTest {

    private static final DisplaySettings SETTINGS = settings(
            List.of(
                    "&7Rank: %rank%", "&7Team: &f%team%", " ",
                    "&eCoin: %coin%", "&5Shards: %shards%", " ",
                    "&7Playtime: &f%playtime%", "&7Online: &f%online%", " ",
                    "&b%server_address%"));
    private static final Rank ADMIN = new Rank("Admin", Component.text("Admin", NamedTextColor.RED), 40, true);
    private static final PlayerProfile PROFILE = new PlayerProfile(3_938_400L, 100, 32);

    @Test
    void rendersCompactDesignWithRealDataAndNoUsernameOrUnrequestedStats() {
        final DisplaySnapshot snapshot = DisplaySnapshot.of(
                PROFILE, ADMIN, "Northwind", 12_500L, 250L, true, 15, 100);
        final List<Component> lines = DisplayLayout.sidebar(snapshot, SETTINGS);

        assertEquals(10, lines.size());
        assertEquals("Rank: Admin", plain(lines, 0));
        assertEquals("Team: Northwind", plain(lines, 1));
        assertEquals(" ", plain(lines, 2));
        assertEquals("Coin: 12,500", plain(lines, 3));
        assertEquals("Shards: 250", plain(lines, 4));
        assertEquals(" ", plain(lines, 5));
        assertEquals("Playtime: 2d 6h 42m", plain(lines, 6));
        assertEquals("Online: 15/100", plain(lines, 7));
        assertEquals(" ", plain(lines, 8));
        assertEquals("Sweats.net", plain(lines, 9));
        assertTrue(lines.stream().noneMatch(line -> TestCommandSenders.plain(line).contains("Username")));
        assertEquals("DONUT SMP", TestCommandSenders.plain(DisplayLayout.sidebarTitle(SETTINGS)));
    }

    @Test
    void preservesRankComponentColorsAndCurrencyColors() {
        final List<Component> lines = DisplayLayout.sidebar(
                DisplaySnapshot.of(PROFILE, ADMIN, "None", 12_500L, 250L, true, 15, 100), SETTINGS);

        assertEquals(NamedTextColor.RED, colorOf(lines.get(0), "Admin"));
        assertEquals(NamedTextColor.YELLOW, colorOf(lines.get(3), "12,500"));
        assertEquals(NamedTextColor.LIGHT_PURPLE, colorOf(lines.get(4), "250"));
        assertEquals("Team: None", plain(lines, 1));
    }

    @Test
    void preservesThePersonalSweatPlusColorInTheUnbracketedRankName() {
        final Rank sweatPlus = new Rank(
                "Sweat+", Component.text("Sweat+", NamedTextColor.LIGHT_PURPLE), 30, true);
        final DisplaySnapshot snapshot = DisplaySnapshot.of(
                PROFILE, sweatPlus, NamedTextColor.AQUA, "None", 0L, 0L, true, 1, 20);

        final Component rankLine = DisplayLayout.sidebar(snapshot, SETTINGS).get(0);
        assertEquals("Rank: Sweat+", TestCommandSenders.plain(rankLine));
        assertEquals(NamedTextColor.LIGHT_PURPLE, colorOf(rankLine, "Sweat"));
        assertEquals(NamedTextColor.AQUA, colorOf(rankLine, "+"));
    }

    @Test
    void doesNotPretendAnUnloadedCurrencyAccountHasZeroBalance() {
        final DisplaySnapshot snapshot = DisplaySnapshot.of(
                PROFILE, ADMIN, "None", 0L, 0L, false, 1, 100);
        final List<Component> lines = DisplayLayout.sidebar(snapshot, SETTINGS);

        assertEquals("Coin: …", plain(lines, 3));
        assertEquals("Shards: …", plain(lines, 4));
    }

    @Test
    void supportsConfiguredTemplatesAndLegacyColors() {
        final DisplaySettings custom = settings(List.of("&a%team% | %online%", "&d%playtime%"));
        final DisplaySnapshot snapshot = DisplaySnapshot.of(
                PROFILE, ADMIN, "Team Name", 0L, 0L, true, 1, 20);
        final List<Component> lines = DisplayLayout.sidebar(snapshot, custom);

        assertEquals("Team Name | 1/20", plain(lines, 0));
        assertEquals(NamedTextColor.GREEN, colorOf(lines.get(0), "Team Name"));
        assertEquals("2d 6h 42m", plain(lines, 1));
        assertEquals(NamedTextColor.LIGHT_PURPLE, colorOf(lines.get(1), "2d 6h 42m"));
    }

    @Test
    void keepsExistingTablistHeaderAndFooter() {
        assertEquals(
                "✦ S W E A T S ✦\nSURVIVAL MULTIPLAYER\n\nWelcome to Sweats!",
                TestCommandSenders.plain(DisplayLayout.tabListHeader(SETTINGS)));
        assertEquals(
                "ONLINE PLAYERS: 15\nYOUR PING: 42 ms\n\nSweats.net",
                TestCommandSenders.plain(DisplayLayout.tabListFooter(15, 42, SETTINGS)));
    }

    private static DisplaySettings settings(final List<String> lines) {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("scoreboard.lines", lines);
        config.set("scoreboard.title", "&6&lDONUT SMP");
        config.set("server.address", "Sweats.net");
        return DisplaySettings.from(config);
    }

    private static String plain(final List<Component> lines, final int index) {
        return TestCommandSenders.plain(lines.get(index));
    }

    private static TextColor colorOf(final Component component, final String content) {
        if (component instanceof final TextComponent text && text.content().equals(content)) {
            return text.color();
        }
        for (final Component child : component.children()) {
            final TextColor color = colorOf(child, content);
            if (color != null) {
                return color;
            }
        }
        return component.color();
    }
}
