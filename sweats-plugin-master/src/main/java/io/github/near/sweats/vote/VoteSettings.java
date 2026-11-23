package io.github.near.sweats.vote;

import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/** Settings for the embedded Votifier listener, read from the {@code votes} config section. */
public record VoteSettings(
        boolean enabled,
        String host,
        int port,
        String token,
        boolean allowV1,
        Path privateKey,
        String broadcast,
        List<String> rewards) {

    public static VoteSettings from(final FileConfiguration config, final Path dataFolder) {
        final String keyPath = config.getString("votes.private-key", "vote/private.key");
        return new VoteSettings(
                config.getBoolean("votes.enabled", true),
                text(config.getString("votes.host"), "0.0.0.0"),
                config.getInt("votes.port", 25536),
                text(config.getString("votes.token"), ""),
                config.getBoolean("votes.allow-v1", true),
                dataFolder.resolve(text(keyPath, "vote/private.key")),
                text(config.getString("votes.broadcast"), ""),
                config.getStringList("votes.rewards"));
    }

    /** Returns a copy with an imported protocol v2 token filled in. */
    public VoteSettings withToken(final String newToken) {
        return new VoteSettings(
                enabled, host, port, newToken, allowV1, privateKey, broadcast, rewards);
    }

    private static String text(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
