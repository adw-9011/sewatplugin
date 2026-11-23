package io.github.near.sweats.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.leaderboard.LeaderboardBoards;
import io.github.near.sweats.leaderboard.LeaderboardCategory;
import io.github.near.sweats.leaderboard.LeaderboardStore;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeaderboardCommandTest {

    @TempDir
    Path folder;

    private final List<Component> messages = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private FakeBoards boards;
    private LeaderboardCommand command;

    @BeforeEach
    void setUp() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        boards = new FakeBoards();
        command = new LeaderboardCommand(boards, permissions);
    }

    @Test
    void requiresTheLeaderboardPermission() {
        final Player sender = TestCommandSenders.player("Alex", 0, false, messages);

        command.onCommand(sender, null, "leaderboard", new String[] {"list"});

        assertEquals("You do not have permission to use this command.", last());
    }

    @Test
    void printsUsageWithoutASubcommand() {
        command.onCommand(TestCommandSenders.console(true, messages), null, "leaderboard",
                new String[0]);

        assertEquals("Leaderboard commands:", TestCommandSenders.plain(messages.getFirst()));
    }

    @Test
    void rejectsUnknownSubcommands() {
        command.onCommand(TestCommandSenders.console(true, messages), null, "leaderboard",
                new String[] {"nope"});

        assertTrue(messages.stream()
                .map(TestCommandSenders::plain)
                .anyMatch(line -> line.equals("Unknown subcommand: nope.")));
    }

    @Test
    void placesABoardAboveThePlayer() {
        final World world = world("world");
        final Player sender = TestCommandSenders.player(
                "Alex", 0, true, messages, new Location(world, 10.5D, 64.0D, 10.5D));

        command.onCommand(sender, null, "leaderboard", new String[] {"place"});

        assertEquals("Placed a Playtime leaderboard at world 11 67 11.", last());
        assertEquals(new Location(world, 10.5D, 67.0D, 10.5D), boards.placed);
        assertEquals(LeaderboardCategory.PLAYTIME, boards.placedCategory);
    }

    @Test
    void placesAChosenCategory() {
        final World world = world("world");
        final Player sender = TestCommandSenders.player(
                "Alex", 0, true, messages, new Location(world, 0.0D, 64.0D, 0.0D));

        command.onCommand(sender, null, "leaderboard", new String[] {"place", "votes"});

        assertEquals("Placed a Total Votes leaderboard at world 0 67 0.", last());
        assertEquals(LeaderboardCategory.VOTES, boards.placedCategory);
    }

    @Test
    void rejectsAnUnknownCategory() {
        final Player sender = TestCommandSenders.player(
                "Alex", 0, true, messages, new Location(world("world"), 0.0D, 64.0D, 0.0D));

        command.onCommand(sender, null, "leaderboard", new String[] {"place", "nope"});

        assertTrue(last().startsWith("Unknown category: nope."));
        assertNull(boards.placed);
    }

    @Test
    void listsTheAvailableCategories() {
        command.onCommand(TestCommandSenders.console(true, messages), null, "leaderboard",
                new String[] {"categories"});

        assertEquals(" - streak - Vote Streak (✦)", last());
        assertTrue(messages.stream().map(TestCommandSenders::plain)
                .anyMatch(line -> line.equals(" - afk - AFK Time (☕)")));
    }

    @Test
    void warnsWhenBoardsAreDisabledInConfig() {
        final Player sender = TestCommandSenders.player(
                "Alex", 0, true, messages,
                new Location(world("world"), 10.5D, 64.0D, 10.5D));
        boards.enabled = false;

        command.onCommand(sender, null, "leaderboard", new String[] {"place"});

        assertEquals(
                "Leaderboards are disabled in config.yml, so nobody can see it yet.", last());
    }

    @Test
    void onlyPlayersCanPlaceOrRemove() {
        final CommandSender console = TestCommandSenders.console(true, messages);

        command.onCommand(console, null, "leaderboard", new String[] {"place"});
        assertTrue(last().startsWith("Only players can place a leaderboard."));

        command.onCommand(console, null, "leaderboard", new String[] {"remove"});
        assertTrue(last().startsWith("Only players can remove a leaderboard."));
    }

    @Test
    void removesTheNearestBoardOrExplainsThatThereIsNone() {
        final Player sender = TestCommandSenders.player(
                "Alex", 0, true, messages,
                new Location(world("world"), 0.0D, 64.0D, 0.0D));

        boards.removed = false;
        command.onCommand(sender, null, "leaderboard", new String[] {"remove"});
        assertEquals("No leaderboard within 8 blocks of you.", last());

        boards.removed = true;
        command.onCommand(sender, null, "leaderboard", new String[] {"remove"});
        assertEquals("Removed the nearest leaderboard.", last());
    }

    @Test
    void listsEveryPlacedBoard() {
        boards.displays.add(new LeaderboardStore.StoredDisplay(
                UUID.randomUUID(), "world", 1.0D, 64.0D, 2.0D));
        boards.displays.add(new LeaderboardStore.StoredDisplay(
                UUID.randomUUID(), "world_nether", -5.0D, 70.0D, 3.0D));

        command.onCommand(TestCommandSenders.console(true, messages), null, "leaderboard",
                new String[] {"list"});

        assertEquals(" - playtime world_nether -5 70 3", last());
        assertTrue(messages.stream()
                .map(TestCommandSenders::plain)
                .anyMatch(line -> line.equals("Leaderboards (2):")));
        assertTrue(messages.stream()
                .map(TestCommandSenders::plain)
                .anyMatch(line -> line.equals(" - playtime world 1 64 2")));
    }

    @Test
    void explainsWhenThereAreNoBoardsYet() {
        command.onCommand(TestCommandSenders.console(true, messages), null, "leaderboard",
                new String[] {"list"});

        assertEquals(
                "There are no leaderboards yet. Use /leaderboard place.", last());
    }

    @Test
    void tabCompletesTheSubcommands() {
        assertEquals(
                List.of("categories", "list", "place", "remove"),
                command.onTabComplete(
                        TestCommandSenders.console(true, messages), null, "leaderboard",
                        new String[] {""}));
        assertEquals(
                List.of("place"),
                command.onTabComplete(
                        TestCommandSenders.console(true, messages), null, "leaderboard",
                        new String[] {"pl"}));
        assertEquals(
                List.of("afk", "deaths", "kills", "playtime", "streak", "votes"),
                command.onTabComplete(
                        TestCommandSenders.console(true, messages), null, "leaderboard",
                        new String[] {"place", ""}));
        assertTrue(command.onTabComplete(
                TestCommandSenders.console(false, messages), null, "leaderboard",
                new String[] {""}).isEmpty());
    }

    private String last() {
        return TestCommandSenders.plain(messages.getLast());
    }

    private static World world(final String name) {
        return (World) Proxy.newProxyInstance(
                LeaderboardCommandTest.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUID" -> java.util.UUID.nameUUIDFromBytes(("world:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> name.hashCode();
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(final Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class FakeBoards implements LeaderboardBoards {

        private final List<LeaderboardStore.StoredDisplay> displays = new ArrayList<>();
        private Location placed;
        private LeaderboardCategory placedCategory;
        private boolean removed;
        private boolean enabled = true;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public List<LeaderboardStore.StoredDisplay> displays() {
            return displays;
        }

        @Override
        public LeaderboardStore.StoredDisplay place(final Location location) {
            return place(location, LeaderboardCategory.PLAYTIME);
        }

        @Override
        public LeaderboardStore.StoredDisplay place(
                final Location location, final LeaderboardCategory category) {
            placed = location;
            placedCategory = category;
            final LeaderboardStore.StoredDisplay stored = new LeaderboardStore.StoredDisplay(
                    UUID.randomUUID(),
                    location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ());
            displays.add(stored);
            return stored;
        }

        @Override
        public boolean removeNearest(final Location location, final double radius) {
            return removed;
        }
    }
}
