package io.github.near.sweats.commands;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PlayerLookup {

    private PlayerLookup() {}

    public static Optional<Target> find(final String name) {
        return find(name, null);
    }

    /**
     * Resolves a player as {@code viewer} is allowed to see them. A vanished online player is
     * treated as unknown instead of falling through to the offline lookup, so ordinary players
     * cannot discover them by name.
     */
    public static Optional<Target> find(final String name, final CommandSender viewer) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getName().equalsIgnoreCase(name)) {
                continue;
            }
            if (viewer instanceof final Player looking && !looking.canSee(player)) {
                return Optional.empty();
            }
            return Optional.of(new Target(player.getUniqueId(), player.getName(), player));
        }

        final OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (!offline.hasPlayedBefore()) {
            return Optional.empty();
        }

        final String storedName = offline.getName();
        return Optional.of(new Target(
                offline.getUniqueId(),
                storedName == null || storedName.isBlank() ? name : storedName,
                null));
    }

    public record Target(UUID id, String name, Player online) {}
}
