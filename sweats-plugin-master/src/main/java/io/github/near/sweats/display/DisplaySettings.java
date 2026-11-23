package io.github.near.sweats.display;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

public record DisplaySettings(
        boolean scoreboardEnabled,
        boolean defaultVisible,
        boolean tablistEnabled,
        String serverName,
        String serverAddress,
        String title,
        List<String> lines,
        long refreshTicks) {

    public static final String DEFAULT_SERVER_NAME = "SURVIVAL MULTIPLAYER";
    public static final String DEFAULT_SERVER_ADDRESS = "sweat.playsmp.xyz";
    public static final String DEFAULT_TITLE = "&6&lDONUT SMP";
    public static final List<String> DEFAULT_LINES = List.of(
            "&7Rank: %rank%",
            "&7Team: &f%team%",
            " ",
            "&eCoin: %coin%",
            "&5Shards: %shards%",
            " ",
            "&7Playtime: &f%playtime%",
            "&7Online: &f%online%",
            " ",
            "&b%server_address%");
    public static final int MAX_LINES = 15;

    public DisplaySettings {
        lines = List.copyOf(lines);
    }

    public static DisplaySettings from(final FileConfiguration config) {
        final String configuredAddress = text(config, "scoreboard.server-address", "");
        final List<String> configuredLines = config.getStringList("scoreboard.lines");
        final List<String> lines = configuredLines.isEmpty() ? DEFAULT_LINES : configuredLines;
        final long refreshTicks = Math.max(20L, Math.min(1_200L,
                config.getLong("scoreboard.refresh-interval", 20L)));
        return new DisplaySettings(
                config.getBoolean("scoreboard.enabled", true),
                config.getBoolean("scoreboard.default-visible", true),
                config.getBoolean("tablist.enabled", true),
                text(config, "server.name", DEFAULT_SERVER_NAME),
                configuredAddress.isBlank()
                        ? text(config, "server.address", DEFAULT_SERVER_ADDRESS)
                        : configuredAddress,
                text(config, "scoreboard.title", DEFAULT_TITLE),
                lines.stream().limit(MAX_LINES).toList(),
                refreshTicks);
    }

    private static String text(final FileConfiguration config, final String path, final String fallback) {
        final String value = config.getString(path);
        return value == null || value.isBlank() ? fallback : value;
    }
}
