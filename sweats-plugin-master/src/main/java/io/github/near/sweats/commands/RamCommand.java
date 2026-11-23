package io.github.near.sweats.commands;

import io.github.near.sweats.ram.RamFormat;
import io.github.near.sweats.ram.RamStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class RamCommand implements CommandExecutor {

    private static final double GOOD_USAGE_PERCENT = 70.0D;
    private static final double WARNING_USAGE_PERCENT = 90.0D;

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (args.length != 0) {
            sender.sendMessage(Component.text("Usage: /" + label, NamedTextColor.RED));
            return true;
        }

        final Runtime runtime = Runtime.getRuntime();
        final RamStats stats = RamStats.from(
                runtime.totalMemory(), runtime.freeMemory(), runtime.maxMemory());

        sender.sendMessage(Component.text()
                .append(Component.text("JVM heap", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.newline())
                .append(line("Used: ", RamFormat.bytes(stats.usedBytes()), NamedTextColor.GREEN))
                .append(Component.newline())
                .append(line("Allocated: ", RamFormat.bytes(stats.allocatedBytes()), NamedTextColor.AQUA))
                .append(Component.newline())
                .append(line(
                        "Max heap: ",
                        stats.hasMaximum() ? RamFormat.bytes(stats.maxBytes()) : "unlimited",
                        stats.hasMaximum() ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY))
                .append(Component.newline())
                .append(line("Heap usage: ", RamFormat.percent(stats), usageColor(stats)))
                .build());
        return true;
    }

    private static Component line(final String label, final String value, final NamedTextColor color) {
        return Component.text(label, NamedTextColor.GRAY)
                .append(Component.text(value, color));
    }

    private static NamedTextColor usageColor(final RamStats stats) {
        if (!stats.hasMaximum()) {
            return NamedTextColor.GRAY;
        }

        final double percent = stats.usedPercent();
        if (percent <= GOOD_USAGE_PERCENT) {
            return NamedTextColor.GREEN;
        }
        return percent <= WARNING_USAGE_PERCENT ? NamedTextColor.YELLOW : NamedTextColor.RED;
    }
}