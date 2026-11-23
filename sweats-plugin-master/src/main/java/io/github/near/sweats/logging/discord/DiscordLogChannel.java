package io.github.near.sweats.logging.discord;

/**
 * The seven Discord log channels. Each maps to a configurable webhook URL.
 * Channel keys match the config.yml section names.
 */
public enum DiscordLogChannel {

    JOIN_LEAVE("join-leave", "Join / Leave Logs", 0x57F287),      // Green
    CHAT_LOGS("chat-logs", "Chat Logs", 0x5865F2),                // Blurple
    PRIVATE_MESSAGES("private-messages", "Private Messages", 0xEB459E), // Pink
    COMMAND_LOGS("command-logs", "Command Logs", 0xFEE75C),       // Yellow
    DUPE_LOGS("dupe-logs", "Dupe / Security Logs", 0xED4245),    // Red
    CONSOLE_LOGS("console-logs", "Console Logs", 0x95A5A6),       // Grey
    TEAM_CHAT_LOGS("team-chat-logs", "Team Chat Logs", 0x1ABC9C); // Teal

    private final String configKey;
    private final String displayName;
    private final int color;

    DiscordLogChannel(final String configKey, final String displayName, final int color) {
        this.configKey = configKey;
        this.displayName = displayName;
        this.color = color;
    }

    public int color() {
        return color;
    }

    public String configKey() {
        return configKey;
    }

    public String displayName() {
        return displayName;
    }

    public static DiscordLogChannel fromConfigKey(final String key) {
        for (final DiscordLogChannel channel : values()) {
            if (channel.configKey.equalsIgnoreCase(key)) {
                return channel;
            }
        }
        return null;
    }
}