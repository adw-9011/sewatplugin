package io.github.near.sweats.friends;

import org.bukkit.configuration.ConfigurationSection;

/** The {@code friends:} section of config.yml, parsed once per reload. */
public record FriendsSettings(
        long requestExpireHours,
        int maxFriends,
        int maxBestFriends,
        int listPageSize,
        int requestCooldownSeconds,
        int notificationRetentionDays,
        int undeliveredNotificationRetentionDays,
        boolean defaultNotificationsEnabled,
        boolean defaultRequestsEnabled,
        int maxNicknameLength) {

    public static FriendsSettings from(final ConfigurationSection config) {
        final ConfigurationSection friends = section(config, "friends");
        return new FriendsSettings(
                Math.max(1L, longValue(friends, "request-expire-hours", 72L)),
                Math.max(0, integer(friends, "max-friends", 200)),
                Math.max(1, integer(friends, "max-best-friends", 10)),
                Math.max(1, integer(friends, "list-page-size", 10)),
                Math.max(0, integer(friends, "request-cooldown-seconds", 3)),
                Math.max(1, integer(friends, "notification-retention-days", 30)),
                Math.max(1, integer(friends, "undelivered-notification-retention-days", 7)),
                bool(friends, "default-notifications-enabled", true),
                bool(friends, "default-requests-enabled", true),
                Math.max(1, integer(friends, "max-nickname-length", 24)));
    }

    public String maxFriendsLabel() {
        return maxFriends > 0 ? Integer.toString(maxFriends) : "unlimited";
    }

    private static ConfigurationSection section(final ConfigurationSection parent, final String path) {
        return parent == null ? null : parent.getConfigurationSection(path);
    }

    private static boolean bool(final ConfigurationSection section, final String path, final boolean fallback) {
        return section == null ? fallback : section.getBoolean(path, fallback);
    }

    private static int integer(final ConfigurationSection section, final String path, final int fallback) {
        return section == null ? fallback : section.getInt(path, fallback);
    }

    private static long longValue(final ConfigurationSection section, final String path, final long fallback) {
        return section == null ? fallback : section.getLong(path, fallback);
    }
}
