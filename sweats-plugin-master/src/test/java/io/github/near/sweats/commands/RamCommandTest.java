package io.github.near.sweats.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class RamCommandTest {

    @Test
    void consoleGetsAHeapReport() {
        final List<Component> messages = new ArrayList<>();
        final CommandSender console = TestCommandSenders.console(true, messages);

        assertTrue(new RamCommand().onCommand(console, null, "ram", new String[0]));

        final String report = TestCommandSenders.plain(messages.getFirst());
        assertTrue(report.startsWith("JVM heap\n"));
        assertTrue(report.contains("Used: "));
        assertTrue(report.contains("Allocated: "));
        assertTrue(report.contains("Max heap: "));
        assertTrue(report.contains("Heap usage: "));
        assertEquals(NamedTextColor.GOLD, TestCommandSenders.colorOf(messages.getFirst(), "JVM heap"));
    }

    @Test
    void rejectsArgumentsWithUsage() {
        final List<Component> messages = new ArrayList<>();
        final CommandSender console = TestCommandSenders.console(true, messages);

        assertTrue(new RamCommand().onCommand(console, null, "ram", new String[] {"extra"}));

        assertEquals("Usage: /ram", TestCommandSenders.plain(messages.getFirst()));
    }
}