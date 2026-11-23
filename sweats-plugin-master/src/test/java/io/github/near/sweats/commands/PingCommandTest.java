package io.github.near.sweats.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.chat.OnlinePlayerNames;
import io.github.near.sweats.ping.PingSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PingCommandTest {

    private static final PingSettings SETTINGS = new PingSettings(100, 200);

    private static PingCommand command(final Function<String, Player> lookup) {
        return new PingCommand(new OnlinePlayerNames(), lookup, () -> SETTINGS);
    }

    @Test
    void playerSeesTheirOwnPing() {
        final List<Component> messages = new ArrayList<>();
        final Player sender = TestCommandSenders.player("Alex", 100, true, messages);

        assertTrue(command(name -> null).onCommand(sender, null, "ping", new String[0]));

        final Component message = messages.getFirst();
        assertEquals("Your ping is: 100 ms", TestCommandSenders.plain(message));
        assertEquals(NamedTextColor.GREEN, TestCommandSenders.colorOf(message, "Your ping is: "));
        assertEquals(NamedTextColor.GREEN, TestCommandSenders.colorOf(message, "100 ms"));
    }

    @Test
    void permittedSenderSeesAnotherPlayersPing() {
        final List<Component> messages = new ArrayList<>();
        final CommandSender console = TestCommandSenders.console(true, messages);
        final Player target = TestCommandSenders.player("Steve", 150, true, new ArrayList<>());

        assertTrue(command(name -> target).onCommand(console, null, "ping", new String[] {"Steve"}));

        final Component message = messages.getFirst();
        assertEquals("Steve's ping is: 150 ms", TestCommandSenders.plain(message));
        assertEquals(NamedTextColor.GREEN, TestCommandSenders.colorOf(message, "Steve's ping is: "));
        assertEquals(NamedTextColor.YELLOW, TestCommandSenders.colorOf(message, "150 ms"));
    }

    @Test
    void offlineTargetIsReportedWithoutLookupFailure() {
        final List<Component> messages = new ArrayList<>();
        final CommandSender console = TestCommandSenders.console(true, messages);

        assertTrue(command(name -> null).onCommand(console, null, "ping", new String[] {"Ghost"}));

        assertEquals("Player 'Ghost' is not online.", TestCommandSenders.plain(messages.getFirst()));
    }

    @Test
    void checkingAnotherPlayerRequiresPermissionBeforeAnyLookup() {
        final List<Component> messages = new ArrayList<>();
        final CommandSender console = TestCommandSenders.console(false, messages);
        final AtomicBoolean lookedUp = new AtomicBoolean();

        assertTrue(command(name -> {
            lookedUp.set(true);
            return null;
        }).onCommand(console, null, "ping", new String[] {"Steve"}));

        assertFalse(lookedUp.get());
        assertEquals(
                "You do not have permission to check other players' ping.",
                TestCommandSenders.plain(messages.getFirst()));
    }

    @Test
    void consoleWithoutArgumentsGetsGuidance() {
        final List<Component> messages = new ArrayList<>();
        final CommandSender console = TestCommandSenders.console(true, messages);

        assertTrue(command(name -> null).onCommand(console, null, "ping", new String[0]));

        assertEquals(
                "Console has no ping. Use /ping <player>.",
                TestCommandSenders.plain(messages.getFirst()));
    }

    @Test
    void rejectsTooManyArguments() {
        final List<Component> messages = new ArrayList<>();
        final CommandSender console = TestCommandSenders.console(true, messages);

        assertTrue(command(name -> null).onCommand(console, null, "ping", new String[] {"a", "b"}));

        assertEquals("Usage: /ping [player]", TestCommandSenders.plain(messages.getFirst()));
    }
}