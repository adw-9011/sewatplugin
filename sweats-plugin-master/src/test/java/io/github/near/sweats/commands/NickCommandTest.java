package io.github.near.sweats.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NickCommandTest {

    @TempDir
    Path folder;

    private final List<Component> messages = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private RankService ranks;
    private NickService nicks;
    private NickCommand command;

    @BeforeEach
    void setUp() {
        ranks = new RankService(folder, warnings::add);
        nicks = new NickService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        command = new NickCommand(nicks, permissions);
    }

    @Test
    void setsAndClearsYourOwnNickname() {
        final Player sender = TestCommandSenders.player("Alex", 0, true, messages);

        command.onCommand(sender, null, "nick", new String[] {"Al"});
        assertEquals("Set Alex's nickname to Al.", last());
        assertEquals("Al", nicks.displayName(sender.getUniqueId(), "Alex"));

        command.onCommand(sender, null, "nick", new String[] {"Al"});
        assertEquals("Alex already has that nickname.", last());

        command.onCommand(sender, null, "nick", new String[] {"off"});
        assertEquals("Cleared Alex's nickname.", last());
        assertEquals("Alex", nicks.displayName(sender.getUniqueId(), "Alex"));
    }

    @Test
    void rejectsInvalidNicknames() {
        final Player sender = TestCommandSenders.player("Alex", 0, true, messages);

        command.onCommand(sender, null, "nick", new String[] {"bad~name"});

        assertTrue(last().startsWith("Nicknames may only contain"));
    }

    @Test
    void requiresTheNickPermission() {
        final Player sender = TestCommandSenders.player("Alex", 0, false, messages);

        command.onCommand(sender, null, "nick", new String[] {"Al"});

        assertEquals("You do not have permission to use this command.", last());
    }

    @Test
    void tabCompletesClearWords() {
        final CommandSender sender = TestCommandSenders.console(true, messages);

        assertEquals(
                List.of("clear", "off", "remove", "reset"),
                command.onTabComplete(sender, null, "nick", new String[] {"Alex", ""}));
    }

    private String last() {
        return TestCommandSenders.plain(messages.getLast());
    }
}
