package io.github.near.sweats.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RankCommandTest {

    @TempDir
    Path folder;

    private final List<Component> messages = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private CommandSender sender;
    private RankService ranks;
    private RankCommand command;

    @BeforeEach
    void setUp() {
        ranks = new RankService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        command = new RankCommand(ranks, permissions);
        sender = TestCommandSenders.console(true, messages);
    }

    @Test
    void createsAndDeletesCustomRanks() {
        run("create", "&cModerator", "25");

        assertEquals("Created rank Moderator with weight 25.", last());
        assertEquals(25, ranks.find("moderator").orElseThrow().weight());

        run("delete", "MODERATOR");

        assertEquals("Deleted rank MODERATOR.", last());
        assertTrue(ranks.find("moderator").isEmpty());
    }

    @Test
    void coreDeletionReturnsTheExactMessage() {
        run("delete", "Default");

        assertEquals("Core system ranks cannot be deleted.", last());
        assertTrue(ranks.find("Default").isPresent());
    }

    @Test
    void listsAndDescribesRanks() {
        run("create", "&cModerator", "25");
        messages.clear();

        run("list");

        assertTrue(messages.stream()
                .map(TestCommandSenders::plain)
                .anyMatch(line -> line.startsWith("[Moderator] weight 25")));

        messages.clear();
        run("info", "moderator");

        assertEquals("[Moderator] Rank information",
                TestCommandSenders.plain(messages.getFirst()));
    }

    @Test
    void rejectsUnknownRanksAndBadInput() {
        run("delete", "Missing");
        assertEquals("Unknown rank: Missing", last());

        run("create", "Bad.Name", "1");
        assertEquals("Rank names may contain letters, numbers, spaces, '_', '+', '-', and '&' formatting codes.",
                last());

        run("create", "Moderator", "-1");
        assertEquals("Rank weight must be zero or greater.", last());
    }

    @Test
    void tabCompletesSubcommands() {
        assertEquals(
                List.of("create", "delete", "info", "list", "set"),
                command.onTabComplete(sender, null, "rank", new String[] {""}));
    }

    private void run(final String... args) {
        command.onCommand(sender, null, "rank", args);
    }

    private String last() {
        return TestCommandSenders.plain(messages.getLast());
    }
}
