package io.github.near.sweats.logging.discord;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Validated Discord logging configuration. Loads webhook URLs from config.yml
 * and provides safe access to them. Invalid or missing URLs simply disable that channel.
 */
public final class DiscordLogConfig {

    /** Hardcoded avatar base URL for player heads. */
    public static final String AVATAR_BASE_URL = "https://crafatar.com/avatars/";

    /** Avatar size parameter. */
    public static final String AVATAR_PARAMS = "?size=64&overlay";

    /** Maximum Discord webhook content length (safety margin under 2000). */
    public static final int MAX_CONTENT_LENGTH = 1900;

    /** Default webhook queue capacity. */
    public static final int DEFAULT_QUEUE_CAPACITY = 512;

    /** Default HTTP timeout in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;

    /** Default retry attempts for failed sends. */
    public static final int DEFAULT_RETRY_ATTEMPTS = 3;

    /** Default retry delay in milliseconds. */
    public static final long DEFAULT_RETRY_DELAY_MILLIS = 500L;

    private final boolean enabled;
    private final Map<DiscordLogChannel, URI> webhooks;
    private final int queueCapacity;
    private final int timeoutSeconds;
    private final int retryAttempts;
    private final long retryDelayMillis;
    private final boolean consoleEnabled;

    private DiscordLogConfig(
            final boolean enabled,
            final Map<DiscordLogChannel, URI> webhooks,
            final int queueCapacity,
            final int timeoutSeconds,
            final int retryAttempts,
            final long retryDelayMillis,
            final boolean consoleEnabled) {
        this.enabled = enabled;
        this.webhooks = Map.copyOf(webhooks);
        this.queueCapacity = queueCapacity;
        this.timeoutSeconds = timeoutSeconds;
        this.retryAttempts = retryAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.consoleEnabled = consoleEnabled;
    }

    public static DiscordLogConfig fromConfig(final FileConfiguration config, final Consumer<String> warn) {
        final EnumMap<DiscordLogChannel, URI> webhooks = new EnumMap<>(DiscordLogChannel.class);

        for (final DiscordLogChannel channel : DiscordLogChannel.values()) {
            final String raw = config.getString("discord-logging.channels." + channel.configKey(), "");
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                final URI uri = URI.create(raw.trim());
                if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                    warn.accept("Discord channel '" + channel.configKey() + "' is not an http(s) URL; ignored");
                    continue;
                }
                if (uri.getHost() == null) {
                    warn.accept("Discord channel '" + channel.configKey() + "' has no host; ignored");
                    continue;
                }
                webhooks.put(channel, uri);
            } catch (final IllegalArgumentException invalid) {
                warn.accept("Discord channel '" + channel.configKey() + "' is not a valid URL; ignored");
            }
        }

        return new DiscordLogConfig(
                config.getBoolean("discord-logging.enabled", false),
                webhooks,
                Math.max(1, Math.min(10000, config.getInt("discord-logging.queue-capacity", DEFAULT_QUEUE_CAPACITY))),
                Math.max(1, Math.min(120, config.getInt("discord-logging.timeout-seconds", DEFAULT_TIMEOUT_SECONDS))),
                Math.max(0, Math.min(10, config.getInt("discord-logging.retry-attempts", DEFAULT_RETRY_ATTEMPTS))),
                Math.max(0L, Math.min(10000L, config.getLong("discord-logging.retry-delay-millis", DEFAULT_RETRY_DELAY_MILLIS))),
                config.getBoolean("discord-logging.console.enabled", true)
        );
    }

    public static DiscordLogConfig disabled() {
        return new DiscordLogConfig(false, Map.of(), DEFAULT_QUEUE_CAPACITY, DEFAULT_TIMEOUT_SECONDS, 0, 0L, false);
    }

    public boolean isEnabled() {
        return enabled && !webhooks.isEmpty();
    }

    public boolean hasChannel(final DiscordLogChannel channel) {
        return enabled && webhooks.containsKey(channel);
    }

    public URI getWebhook(final DiscordLogChannel channel) {
        return webhooks.get(channel);
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public int retryAttempts() {
        return retryAttempts;
    }

    public long retryDelayMillis() {
        return retryDelayMillis;
    }

    public boolean consoleEnabled() {
        return consoleEnabled;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof DiscordLogConfig that)) return false;
        return enabled == that.enabled && queueCapacity == that.queueCapacity && timeoutSeconds == that.timeoutSeconds
                && retryAttempts == that.retryAttempts && retryDelayMillis == that.retryDelayMillis
                && consoleEnabled == that.consoleEnabled && Objects.equals(webhooks, that.webhooks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, webhooks, queueCapacity, timeoutSeconds, retryAttempts, retryDelayMillis, consoleEnabled);
    }
}