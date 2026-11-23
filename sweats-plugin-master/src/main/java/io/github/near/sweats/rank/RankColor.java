package io.github.near.sweats.rank;

import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

public final class RankColor {

    private RankColor() {}

    public static TextColor parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        final String value = raw.trim();
        final String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.matches("[0-9A-Fa-f]{6}")) {
            return TextColor.color(Integer.parseInt(hex, 16));
        }

        final String name = value.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return NamedTextColor.NAMES.value(name);
    }

    public static String serialize(final TextColor color) {
        if (color instanceof final NamedTextColor named) {
            final String key = NamedTextColor.NAMES.key(named);
            if (key != null) {
                return key;
            }
        }
        return String.format("#%06X", color.value());
    }
}
