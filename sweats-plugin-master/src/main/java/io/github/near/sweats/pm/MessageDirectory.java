package io.github.near.sweats.pm;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Resolves private-message recipients. Implementations must hide vanished players. */
public interface MessageDirectory {

    /** Finds a player the viewer may see, by current account name. */
    Optional<Player> findVisible(CommandSender viewer, String name);

    /** Resolves an online player by UUID, used by /r. */
    Optional<Player> online(UUID id);
}
