package io.github.near.sweats.runes;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Sends the SweatyRunes resource pack to players when they join. */
public final class RunePackSender implements Listener {

    private static final UUID PACK_ID =
            UUID.nameUUIDFromBytes("sweatyrunes-pack".getBytes(StandardCharsets.UTF_8));

    private final JavaPlugin plugin;
    private boolean warned;

    public RunePackSender(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("runes.resource-pack.enabled", true)) {
            return;
        }
        final String url = config.getString("runes.resource-pack.url", "").trim();
        final String sha1 = config.getString("runes.resource-pack.sha1", "").trim();
        if (url.isEmpty() || !sha1.matches("[0-9a-fA-F]{40}")) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning(
                        "runes.resource-pack.url / sha1 not set in config.yml - the Rune Table model will not show.");
            }
            return;
        }
        final ValidationResult validation = validate(url);
        if (!validation.valid()) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning(
                        "runes.resource-pack.url invalid: " + validation.reason()
                                + " - the Rune Table model will not show.");
            }
            return;
        }
        final byte[] hash = new byte[20];
        for (int index = 0; index < 20; index++) {
            hash[index] = (byte) Integer.parseInt(
                    sha1.substring(index * 2, index * 2 + 2), 16);
        }
        final boolean required = config.getBoolean("runes.resource-pack.required", false);
        final String prompt = config.getString(
                "runes.resource-pack.prompt",
                "SweatyRunes needs its resource pack for custom models.");
        final Player player = event.getPlayer();
        plugin.getServer()
                .getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            if (player.isOnline()) {
                                player.setResourcePack(PACK_ID, url, hash, prompt, required);
                            }
                        },
                        20L);
    }

    private static final int MAX_URL_LENGTH = 2048;

    private record ValidationResult(boolean valid, String reason) {}

    /**
     * Validates the resource-pack URL before sending it to players.
     *
     * <p>Accepts HTTPS only (no HTTP, no custom schemes). Rejects URLs longer than
     * 2048 characters. Validates the SHA-1 hash format (40 hex chars) was already
     * checked by the caller. This keeps players from being prompted with malicious
     * or malformed download links.
     */
    private ValidationResult validate(final String url) {
        // Length
        if (url.length() > MAX_URL_LENGTH) {
            return new ValidationResult(false, "URL exceeds " + MAX_URL_LENGTH + " characters");
        }
        // Scheme
        try {
            final URL parsed = new URL(url);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                return new ValidationResult(false, "only HTTPS URLs are accepted");
            }
            // Ensure it's an absolute URL with a host
            if (parsed.getHost() == null || parsed.getHost().isEmpty()) {
                return new ValidationResult(false, "missing host");
            }
        } catch (final MalformedURLException exception) {
            return new ValidationResult(false, "malformed URL");
        }
        return new ValidationResult(true, "");
    }
}
