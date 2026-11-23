package io.github.near.sweats.display;

import io.github.near.sweats.currency.CurrencyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class DisplayLayout {

    static final int SIDEBAR_LINES = DisplaySettings.MAX_LINES;
    private static final Pattern PLACEHOLDER = Pattern.compile("%(rank|team|coin|shards|playtime|online|server_address)%");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Map<Character, NamedTextColor> LEGACY_COLORS = Map.ofEntries(
            Map.entry('0', NamedTextColor.BLACK),
            Map.entry('1', NamedTextColor.DARK_BLUE),
            Map.entry('2', NamedTextColor.DARK_GREEN),
            Map.entry('3', NamedTextColor.DARK_AQUA),
            Map.entry('4', NamedTextColor.DARK_RED),
            Map.entry('5', NamedTextColor.DARK_PURPLE),
            Map.entry('6', NamedTextColor.GOLD),
            Map.entry('7', NamedTextColor.GRAY),
            Map.entry('8', NamedTextColor.DARK_GRAY),
            Map.entry('9', NamedTextColor.BLUE),
            Map.entry('a', NamedTextColor.GREEN),
            Map.entry('b', NamedTextColor.AQUA),
            Map.entry('c', NamedTextColor.RED),
            Map.entry('d', NamedTextColor.LIGHT_PURPLE),
            Map.entry('e', NamedTextColor.YELLOW),
            Map.entry('f', NamedTextColor.WHITE));

    private DisplayLayout() {}

    static Component sidebarTitle(final DisplaySettings settings) {
        return LEGACY.deserialize(settings.title());
    }

    static List<Component> sidebar(final DisplaySnapshot snapshot, final DisplaySettings settings) {
        final List<Component> rendered = new ArrayList<>(settings.lines().size());
        for (final String line : settings.lines()) {
            rendered.add(render(line, snapshot, settings));
        }
        return List.copyOf(rendered);
    }

    private static Component render(
            final String template, final DisplaySnapshot snapshot, final DisplaySettings settings) {
        final Matcher matcher = PLACEHOLDER.matcher(template);
        Component line = Component.empty();
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                line = line.append(LEGACY.deserialize(template.substring(cursor, matcher.start())));
            }
            line = line.append(value(matcher.group(1), template, matcher.start(), snapshot, settings));
            cursor = matcher.end();
        }
        if (cursor < template.length()) {
            line = line.append(LEGACY.deserialize(template.substring(cursor)));
        }
        return line;
    }

    private static Component value(
            final String key,
            final String template,
            final int placeholderStart,
            final DisplaySnapshot snapshot,
            final DisplaySettings settings) {
        return switch (key) {
            // The rank's configured component is authoritative; do not recolor it from the template.
            case "rank" -> snapshot.rank().displayName(snapshot.plusColor());
            // Currency colors are fixed by the feature requirement, independently of line styling.
            case "coin" -> Component.text(snapshot.coin(), CurrencyType.SWEAT_COINS.color());
            case "shards" -> Component.text(snapshot.shards(), CurrencyType.TRYHARD_SHARDS.color());
            case "team" -> Component.text(snapshot.team(), colorAt(template, placeholderStart, NamedTextColor.WHITE));
            case "playtime" -> Component.text(
                    snapshot.playtime(), colorAt(template, placeholderStart, NamedTextColor.WHITE));
            case "online" -> Component.text(
                    snapshot.online(), colorAt(template, placeholderStart, NamedTextColor.WHITE));
            case "server_address" -> Component.text(
                    settings.serverAddress(), colorAt(template, placeholderStart, NamedTextColor.AQUA));
            default -> Component.empty();
        };
    }

    /** Applies the most recent legacy color code preceding a placeholder, if present. */
    private static TextColor colorAt(
            final String text, final int end, final NamedTextColor fallback) {
        NamedTextColor color = null;
        for (int index = 0; index + 1 < end; index++) {
            if (text.charAt(index) != '&') {
                continue;
            }
            final char code = Character.toLowerCase(text.charAt(index + 1));
            final NamedTextColor next = LEGACY_COLORS.get(code);
            if (next != null) {
                color = next;
            } else if (code == 'r') {
                color = NamedTextColor.WHITE;
            }
            index++;
        }
        return color == null ? fallback : color;
    }
}
