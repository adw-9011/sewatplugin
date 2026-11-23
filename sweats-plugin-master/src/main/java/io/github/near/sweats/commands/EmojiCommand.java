package io.github.near.sweats.commands;

import io.github.near.sweats.chat.Emojis;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** /emoji - lists every chat emoji and the code that types it. */
public final class EmojiCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        final TextComponent.Builder out = Component.text()
                .append(Component.text("Chat emojis", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" - type the code in chat, click one to copy it",
                        NamedTextColor.GRAY));

        for (final Emojis.Emoji emoji : Emojis.all()) {
            final Component code = Component.text(emoji.code(), NamedTextColor.YELLOW)
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Click to put " + emoji.code() + " in your chat box",
                                    NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.suggestCommand(emoji.code()));
            out.append(Component.newline())
                    .append(code)
                    .append(Component.text("  \u2192  ", NamedTextColor.DARK_GRAY))
                    .append(emoji.display());
        }

        sender.sendMessage(out.build());
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        return List.of();
    }
}
