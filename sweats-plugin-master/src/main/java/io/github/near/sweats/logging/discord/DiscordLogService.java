package io.github.near.sweats.logging.discord;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Central Discord logging service. Routes server events to seven independent
 * Discord webhook channels using CV2 payloads (grey theme, no side colors).
 *
 * <p>Channels:
 * <ol>
 *   <li>{@link DiscordLogChannel#JOIN_LEAVE} - Player join/leave with avatar</li>
 *   <li>{@link DiscordLogChannel#CHAT_LOGS} - Public chat messages</li>
 *   <li>{@link DiscordLogChannel#PRIVATE_MESSAGES} - Private messages (/msg, /r)</li>
 *   <li>{@link DiscordLogChannel#COMMAND_LOGS} - Command executions</li>
 *   <li>{@link DiscordLogChannel#DUPE_LOGS} - Dupe/security detections</li>
 *   <li>{@link DiscordLogChannel#CONSOLE_LOGS} - Server console output</li>
 *   <li>{@link DiscordLogChannel#TEAM_CHAT_LOGS} - Team chat messages</li>
 * </ol>
 *
 * <p>All network I/O happens on a bounded background worker. Main thread never blocks.
 * Console logging uses a java.util.logging Handler that filters out this package's own logs.
 */
public final class DiscordLogService implements Listener, AutoCloseable {

    private static final Logger ROOT_LOGGER = Logger.getLogger("");
    private static final SimpleFormatter FORMATTER = new SimpleFormatter();

    private final JavaPlugin plugin;
    private final Consumer<String> warn;
    private final ConsoleHandler consoleHandler = new ConsoleHandler();

    private volatile DiscordLogConfig config = DiscordLogConfig.disabled();
    private volatile DiscordLogClient client;
    private boolean consoleHandlerInstalled;

    public DiscordLogService(final JavaPlugin plugin, final Consumer<String> warn) {
        this.plugin = plugin;
        this.warn = warn;
        reload();
    }

    /**
     * Rebuilds configuration and webhook client from config.yml.
     * Safe to call multiple times; old client is shut down cleanly.
     */
    public synchronized void reload() {
        config = DiscordLogConfig.fromConfig(plugin.getConfig(), warn);
        final DiscordLogClient old = client;
        client = null;
        if (old != null) {
            old.close();
        }
        if (config.isEnabled()) {
            client = new DiscordLogClient(config, warn);
        }
        syncConsoleHandler();
    }

    public boolean isConfigured() {
        return config.isEnabled();
    }

    public DiscordLogConfig config() {
        return config;
    }

    // ==================== Event Listeners ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final String avatarUrl = DiscordLogConfig.AVATAR_BASE_URL + player.getUniqueId() + DiscordLogConfig.AVATAR_PARAMS;
        final String content = "**" + sanitize(player.getName()) + "** joined\nUUID: `" + player.getUniqueId() + "`";
        sendWithThumbnail(DiscordLogChannel.JOIN_LEAVE, content, avatarUrl);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final String avatarUrl = DiscordLogConfig.AVATAR_BASE_URL + player.getUniqueId() + DiscordLogConfig.AVATAR_PARAMS;
        final String content = "**" + sanitize(player.getName()) + "** left\nUUID: `" + player.getUniqueId() + "`";
        sendWithThumbnail(DiscordLogChannel.JOIN_LEAVE, content, avatarUrl);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        final String content = "**" + sanitize(event.getPlayer().getName()) + "**\n" + sanitize(message);
        // Chat logs: plain content, no embed
        if (config.hasChannel(DiscordLogChannel.CHAT_LOGS) && client != null) {
            client.enqueue(DiscordLogChannel.CHAT_LOGS, DiscordLogPayload.content(content));
        }
    }

    /**
     * Logs command executions. Captures whether the command was cancelled (FALSE)
     * or not cancelled (UNKNOWN). Bukkit has no generic command result event.
     *
     * <p>Sensitive commands (login, register, changepass, msg, r, etc.) have their
     * arguments redacted entirely.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (!config.hasChannel(DiscordLogChannel.COMMAND_LOGS)) {
            return;
        }
        final String raw = event.getMessage() == null ? "" : event.getMessage().trim();
        final String label = extractCommandLabel(raw);
        final boolean sensitive = isSensitiveCommand(label);
        if (sensitive) {
            return; // Skip logging sensitive commands entirely
        }
        final String shown = sanitize(raw.startsWith("/") ? raw : "/" + raw);
        final String result = event.isCancelled() ? "FALSE" : "UNKNOWN";
        final String content = "**" + sanitize(event.getPlayer().getName()) + "** ran `" + shown + "`\nResult: **" + result + "**\nResponse: not captured";
        send(DiscordLogChannel.COMMAND_LOGS, content);
    }

    // ==================== Explicit Log Methods ====================

    /** Logs a private message to the dedicated PM channel. */
    public void logPrivateMessage(final String from, final String to, final String content) {
        send(DiscordLogChannel.PRIVATE_MESSAGES,
                "**" + sanitize(from) + " → " + sanitize(to) + "**\n" + sanitize(content));
    }

    /** Logs a team chat message. */
    public void logTeamChat(final String team, final String player, final String content) {
        final String formatted = "team: " + sanitize(team) + "\nplayer: " + sanitize(player) + "\nmessage: " + sanitize(content);
        if (config.hasChannel(DiscordLogChannel.TEAM_CHAT_LOGS) && client != null) {
            // Use embed without color (0 = no side bar)
            client.enqueue(DiscordLogChannel.TEAM_CHAT_LOGS,
                    DiscordLogPayload.embed(formatted, 0, "Team Chat", null, null, null));
        }
    }

    /** Logs a dupe/security detection. */
    public void logDupe(final String classification, final String player, final String detail) {
        send(DiscordLogChannel.DUPE_LOGS,
                "**" + sanitize(classification) + "**\nPlayer: " + sanitize(player) + "\n" + detail);
    }

    /** Generic send to any channel using classic embed format (no side color). */
    public void send(final DiscordLogChannel channel, final String content) {
        if (!config.hasChannel(channel) || client == null) {
            return;
        }
        final String json = DiscordLogPayload.simple(truncate(content), 0);
        client.enqueue(channel, json);
    }

    /** Send with thumbnail (avatar) - used for join/leave. */
    private void sendWithThumbnail(final DiscordLogChannel channel, final String content, final String thumbnailUrl) {
        if (!config.hasChannel(channel) || client == null) {
            return;
        }
        // Extract title from content (first line before \n)
        String title = "Event";
        String description = content;
        final int newlineIndex = content.indexOf('\n');
        if (newlineIndex > 0) {
            title = content.substring(0, newlineIndex);
            description = content.substring(newlineIndex + 1);
        }
        final String json = DiscordLogPayload.withThumbnail(
                title,
                truncate(description),
                0, // No side color
                thumbnailUrl);
        client.enqueue(channel, json);
    }

    // ==================== Console Capture ====================

    private void syncConsoleHandler() {
        final boolean wanted = config.consoleEnabled() && config.hasChannel(DiscordLogChannel.CONSOLE_LOGS);
        if (wanted && !consoleHandlerInstalled) {
            consoleHandler.setLevel(Level.ALL);
            ROOT_LOGGER.addHandler(consoleHandler);
            consoleHandlerInstalled = true;
        } else if (!wanted && consoleHandlerInstalled) {
            ROOT_LOGGER.removeHandler(consoleHandler);
            consoleHandlerInstalled = false;
        }
    }

    @Override
    public synchronized void close() {
        if (consoleHandlerInstalled) {
            ROOT_LOGGER.removeHandler(consoleHandler);
            consoleHandlerInstalled = false;
        }
        final DiscordLogClient old = client;
        client = null;
        if (old != null) {
            old.close();
        }
    }

    private final class ConsoleHandler extends Handler {

        @Override
        public void publish(final LogRecord record) {
            if (!config.hasChannel(DiscordLogChannel.CONSOLE_LOGS) || client == null) {
                return;
            }
            if (record.getLevel().intValue() < Level.INFO.intValue()) {
                return;
            }
            // Prevent recursive logging from our own Discord client
            final String source = record.getSourceClassName();
            if (source != null && source.startsWith("io.github.near.sweats.logging.discord")) {
                return;
            }
            final String message;
            try {
                message = FORMATTER.formatMessage(record);
            } catch (final Exception ignored) {
                return;
            }
            if (message == null || message.isBlank()) {
                return;
            }
            final String level = record.getLevel().getName().toUpperCase(Locale.ROOT);
            final String content = "**CONSOLE** [" + level + "]\n" + sanitize(message);
            send(DiscordLogChannel.CONSOLE_LOGS, content);
        }

        @Override
        public void flush() {
            // Worker handles flushing
        }

        @Override
        public void close() {
            // Detached by DiscordLogService.close()
        }
    }

    // ==================== Utilities ====================

    private static String extractCommandLabel(final String raw) {
        final String trimmed = raw.trim();
        final String withoutSlash = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        final int space = withoutSlash.indexOf(' ');
        final String label = space < 0 ? withoutSlash : withoutSlash.substring(0, space);
        return label.toLowerCase(Locale.ROOT);
    }

    private static boolean isSensitiveCommand(final String label) {
        return switch (label) {
            case "login", "l", "register", "changepass", "changepassword", "premium",
                 "msg", "message", "tell", "whisper", "w", "r", "reply",
                 "pc", "teamchat", "tc", "sweatschat", "sc" -> true;
            default -> false;
        };
    }

    private static String sanitize(final String input) {
        if (input == null) return "";
        // Escape Discord mentions
        String result = input.replace("@everyone", "@\u200Beveryone").replace("@here", "@\u200Bhere");
        // Remove control chars except newlines/tabs
        final StringBuilder out = new StringBuilder(Math.min(result.length(), DiscordLogConfig.MAX_CONTENT_LENGTH + 16));
        for (int i = 0; i < result.length(); i++) {
            final char c = result.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                out.append(c);
            }
            if (out.length() >= DiscordLogConfig.MAX_CONTENT_LENGTH) break;
        }
        if (out.length() >= DiscordLogConfig.MAX_CONTENT_LENGTH) {
            return out.substring(0, Math.max(0, DiscordLogConfig.MAX_CONTENT_LENGTH - 3)) + "...";
        }
        return out.toString();
    }

    private static String truncate(final String input) {
        if (input == null) return "";
        if (input.length() <= DiscordLogConfig.MAX_CONTENT_LENGTH) return input;
        return input.substring(0, Math.max(0, DiscordLogConfig.MAX_CONTENT_LENGTH - 3)) + "...";
    }
}