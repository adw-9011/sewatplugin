package io.github.near.sweats.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code auth:} section of config.yml, parsed once per reload.
 *
 * <p>The record is deliberately immutable: the running {@link AuthService} swaps the whole
 * instance on reload instead of mutating individual fields.
 */
public record AuthConfig(
        boolean enabled,
        String world,
        boolean useWorldSpawn,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        int minPasswordLength,
        int maxPasswordLength,
        int loginTimeoutSeconds,
        int reminderSeconds,
        int maxFailedAttempts,
        int lockoutSeconds,
        long attemptCooldownMillis,
        long registerCooldownMillis,
        boolean blindness,
        boolean hidePlayers,
        boolean blockChat,
        boolean blockCommands,
        boolean sessionEnabled,
        long sessionDurationMillis,
        boolean sessionBindIp,
        boolean premiumEnabled,
        PremiumMode premiumMode,
        boolean premiumVerifyMojang,
        boolean premiumSuggestion,
        List<String> premiumTrustedProxies,
        boolean importSimpleLogin,
        String simpleLoginDatabase,
        AuthMessages messages) {

    /** What the proxy / UUID checks are allowed to trust. */
    public enum PremiumMode {
        /** Every player has to log in. */
        OFF,
        /** Trust UUID version 4, which an online-mode server or forwarding proxy produces. */
        PROXY,
        /** Trust UUID version 4 only after the Mojang API confirms the name and UUID. */
        MOJANG;

        static PremiumMode from(final String value) {
            if (value == null) {
                return PROXY;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException exception) {
                return PROXY;
            }
        }
    }

    public static AuthConfig from(final ConfigurationSection config) {
        final ConfigurationSection auth = section(config, "auth");
        final ConfigurationSection location = section(auth, "location");
        final ConfigurationSection session = section(auth, "session");
        final ConfigurationSection premium = section(auth, "premium");

        final int minLength = clamp(integer(auth, "min-password-length", 8), 4, 1024);
        final int maxLength = clamp(integer(auth, "max-password-length", 128), minLength, 1024);

        final long sessionHours = Math.max(0L, longValue(session, "duration-hours", 168L));
        final long sessionMillis = Math.min(sessionHours, 24L * 365L) * 3_600_000L;

        return new AuthConfig(
                bool(auth, "enabled", true),
                string(location, "world", ""),
                bool(location, "use-world-spawn", true),
                decimal(location, "x", 0.5),
                decimal(location, "y", 100.0),
                decimal(location, "z", 0.5),
                (float) decimal(location, "yaw", 0.0),
                (float) decimal(location, "pitch", 0.0),
                minLength,
                maxLength,
                clamp(integer(auth, "login-timeout-seconds", 300), 0, 86_400),
                clamp(integer(auth, "reminder-seconds", 5), 1, 3_600),
                clamp(integer(auth, "max-failed-attempts", 5), 1, 1_000),
                clamp(integer(auth, "lockout-seconds", 60), 0, 86_400),
                clamp(longValue(auth, "attempt-cooldown-ms", 750L), 0L, 60_000L),
                clamp(longValue(auth, "register-cooldown-ms", 5_000L), 0L, 60_000L),
                bool(auth, "blindness", true),
                bool(auth, "hide-players", true),
                bool(auth, "block-chat", true),
                bool(auth, "block-commands", true),
                bool(session, "enabled", true),
                sessionMillis,
                bool(session, "bind-ip", true),
                bool(premium, "enabled", true),
                PremiumMode.from(string(premium, "mode", "PROXY")),
                bool(premium, "verify-mojang", true),
                bool(premium, "suggestion", true),
                trustedProxies(premium),
                bool(auth, "import-simplelogin", true),
                string(auth, "simplelogin-database", "../SimpleLogin/passwords.db"),
                AuthMessages.from(section(auth, "messages")));
    }

    /** Literal IPs and CIDR blocks; malformed entries are ignored by {@link ProxyTrust}. */
    private static List<String> trustedProxies(final ConfigurationSection premium) {
        if (premium == null) {
            return List.of();
        }
        final List<String> configured = premium.getStringList("trusted-proxies");
        final List<String> result = new ArrayList<>(configured.size());
        for (final String entry : configured) {
            if (entry != null && !entry.isBlank()) {
                result.add(entry.trim());
            }
        }
        return List.copyOf(result);
    }

    private static ConfigurationSection section(
            final ConfigurationSection parent, final String path) {
        return parent == null ? null : parent.getConfigurationSection(path);
    }

    private static boolean bool(
            final ConfigurationSection section, final String path, final boolean fallback) {
        return section == null ? fallback : section.getBoolean(path, fallback);
    }

    private static int integer(
            final ConfigurationSection section, final String path, final int fallback) {
        return section == null ? fallback : section.getInt(path, fallback);
    }

    private static long longValue(
            final ConfigurationSection section, final String path, final long fallback) {
        return section == null ? fallback : section.getLong(path, fallback);
    }

    private static double decimal(
            final ConfigurationSection section, final String path, final double fallback) {
        return section == null ? fallback : section.getDouble(path, fallback);
    }

    private static String string(
            final ConfigurationSection section, final String path, final String fallback) {
        final String value = section == null ? null : section.getString(path);
        return value == null ? fallback : value;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(final long value, final long min, final long max) {
        return Math.max(min, Math.min(max, value));
    }
}
