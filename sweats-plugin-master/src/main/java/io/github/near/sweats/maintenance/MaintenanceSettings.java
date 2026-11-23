package io.github.near.sweats.maintenance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code maintenance:} section of config.yml.
 *
 * <p>Both texts are MiniMessage. They are parsed once per reload so a busy server list ping does
 * not pay for parsing every time.
 */
public record MaintenanceSettings(Component motd, Component kickMessage) {

    public static final String DEFAULT_MOTD =
            "<red><bold>Maintenance</bold></red>\n"
                    + "<gray>We are updating the server. Please check back soon.</gray>";
    public static final String DEFAULT_KICK_MESSAGE =
            "<red>Server is under maintenance.</red>\n"
                    + "<gray>Please check back soon.</gray>";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public static MaintenanceSettings from(final ConfigurationSection config) {
        return new MaintenanceSettings(
                parse(config.getString("maintenance.motd"), DEFAULT_MOTD),
                parse(config.getString("maintenance.kick-message"), DEFAULT_KICK_MESSAGE));
    }

    private static Component parse(final String value, final String fallback) {
        return MINI_MESSAGE.deserialize(value == null || value.isBlank() ? fallback : value);
    }
}
