package io.github.near.sweats.chat;

import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.FileConfiguration;

/** Config for the chat mention highlighter. */
public record MentionSettings(boolean enabled, TextColor color) {

    private static final TextColor DEFAULT_COLOR = NamedTextColor.YELLOW;

    public static MentionSettings from(final FileConfiguration config) {
        return new MentionSettings(
                config.getBoolean("mentions.enabled", true),
                parseColor(config.getString("mentions.color"), DEFAULT_COLOR));
    }

    /** Accepts a MiniMessage color name ({@code yellow}, {@code gold}) or hex ({@code #ffd700}). */
    static TextColor parseColor(final String raw, final TextColor fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        final String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) {
            final TextColor hex = TextColor.fromHexString(value);
            return hex != null ? hex : fallback;
        }

        final NamedTextColor named = NamedTextColor.NAMES.value(value);
        return named != null ? named : fallback;
    }
}
