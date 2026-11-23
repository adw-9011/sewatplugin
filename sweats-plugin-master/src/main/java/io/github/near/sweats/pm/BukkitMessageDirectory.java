package io.github.near.sweats.pm;

import io.github.near.sweats.commands.PlayerLookup;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Production recipient lookup: vanish-aware by name, online-only by UUID. */
public final class BukkitMessageDirectory implements MessageDirectory {

    @Override
    public Optional<Player> findVisible(final CommandSender viewer, final String name) {
        return PlayerLookup.find(name, viewer)
                .map(PlayerLookup.Target::online)
                .filter(player -> player != null && player.isOnline());
    }

    @Override
    public Optional<Player> online(final UUID id) {
        final Player player = Bukkit.getPlayer(id);
        return player != null && player.isOnline() ? Optional.of(player) : Optional.empty();
    }
}
