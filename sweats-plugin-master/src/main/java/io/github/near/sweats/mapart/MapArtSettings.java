package io.github.near.sweats.mapart;

import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Config for {@code /gif}: whether image maps are enabled and which folder inside the plugin data
 * directory holds the images.
 *
 * @param enabled whether {@code /gif} hands out maps at all
 * @param folder folder name inside {@code plugins/Sweats/}, without any path separators
 */
public record MapArtSettings(boolean enabled, String folder) {

    public static MapArtSettings from(final FileConfiguration config) {
        return new MapArtSettings(
                config.getBoolean("gifs.enabled", true),
                folderName(config.getString("gifs.folder", "gifs")));
    }

    /** Only a plain folder name is accepted, so config cannot point outside the plugin folder. */
    private static String folderName(final String raw) {
        final String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9][a-z0-9._-]{0,31}") ? value : "gifs";
    }
}
