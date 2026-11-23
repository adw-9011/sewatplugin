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

class GrantCommandTest {

    @TempDir
    Path folder;

    private final List<Component> messages = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private CommandSender sender;
    private RankService ranks;
    private PermissionService permissions;
    private GrantCommand command;

    @BeforeEach
    void setUp() {
        ranks = new RankService(folder, warnings::add);
        ranks.create("&6Moderator", 25);
        permissions = new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        command = new GrantCommand(permissions, ranks);
        sender = TestCommandSenders.console(true, messages);
    }

    @Test
    void grantsListsAndRevokesRankPermissions() {
        run("rank", "Moderator", "sweats.banperm");

        assertEquals("Granted sweats.banperm to rank Moderator.", last());
        assertEquals(
                List.of("sweats.banperm"),
                permissions.rankPermissions(ranks.find("Moderator").orElseThrow()));

        run("rank", "Moderator", "sweats.banperm");
        assertEquals("Rank Moderator already has sweats.banperm.", last());

        messages.clear();
        run("list", "rank", "moderator");
        assertEquals("sweats.banperm", last());

        run("revoke", "rank", "Moderator", "sweats.banperm");
        assertEquals("Revoked sweats.banperm from rank Moderator.", last());
        assertTrue(permissions.rankPermissions(ranks.find("Moderator").orElseThrow()).isEmpty());
    }

    @Test
    void rejectsInvalidNodesAndUnknownRanks() {
        run("rank", "Moderator", "not a node");
        assertEquals("Invalid permission node: not a node", last());

        run("rank", "Missing", "sweats.banperm");
        assertEquals("Unknown rank: Missing", last());
    }

    @Test
    void tabCompletesScopesAndRanks() {
        assertEquals(
                List.of("list", "rank", "revoke", "user"),
                command.onTabComplete(sender, null, "grant", new String[] {""}));
        assertEquals(
                List.of("Moderator"),
                command.onTabComplete(sender, null, "grant", new String[] {"rank", "Mod"}));
    }

    @Test
    void tabCompletesPermissionNodes() {
        assertEquals(
                List.of("sweats.nick", "sweats.nick.others"),
                command.onTabComplete(
                        sender, null, "grant",
                        new String[] {"user", "Alex", "sweats.nick"}));
        assertEquals(
                List.of("sweats.nick", "sweats.nick.others"),
                command.onTabComplete(
                        sender, null, "grant",
                        new String[] {"rank", "Moderator", "sweats.nick"}));
    }

    private void run(final String... args) {
        command.onCommand(sender, null, "grant", args);
    }

    private String last() {
        return TestCommandSenders.plain(messages.getLast());
    }
}