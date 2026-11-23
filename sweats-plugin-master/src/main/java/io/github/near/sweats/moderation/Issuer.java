package io.github.near.sweats.moderation;

import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public record Issuer(UUID id, String name) {

    public static Issuer of(final CommandSender sender) {
        if (sender instanceof final Player player) {
            return new Issuer(player.getUniqueId(), player.getName());
        }
        return new Issuer(null, sender.getName());
    }
}
