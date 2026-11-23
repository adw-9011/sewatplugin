package io.github.near.sweats.leaderboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;

/**
 * The visual frame around a leaderboard: a themed block pedestal and a floating icon.
 *
 * <p>Both are display entities, so nothing is written to the world and no block can be broken or
 * picked up. Like the text they are hidden by default and only shown to players near the board.
 */
final class LeaderboardDecor {

    private final BlockDisplay pedestal;
    private final ItemDisplay icon;
    private final Set<UUID> viewers = new HashSet<>();

    private LeaderboardDecor(final BlockDisplay pedestal, final ItemDisplay icon) {
        this.pedestal = pedestal;
        this.icon = icon;
    }

    /** Spawns the decoration, or returns {@code null} when the server cannot create it. */
    static LeaderboardDecor spawn(
            final World world,
            final Location location,
            final LeaderboardCategory category,
            final Logger logger) {
        try {
            final BlockDisplay pedestal = world.spawn(
                    location.clone().add(0.0D, -2.6D, 0.0D), BlockDisplay.class, entity -> {
                        configure(entity);
                        entity.setBlock(category.pedestal().createBlockData());
                    });
            final ItemDisplay icon = world.spawn(
                    location.clone().add(0.0D, -1.6D, 0.0D), ItemDisplay.class, entity -> {
                        configure(entity);
                        entity.setItemStack(new ItemStack(category.icon()));
                    });
            return new LeaderboardDecor(pedestal, icon);
        } catch (final RuntimeException exception) {
            logger.log(Level.FINE, "Could not create leaderboard decoration at "
                    + location + ": " + exception.getMessage());
            return null;
        }
    }

    private static void configure(final Display entity) {
        entity.setVisibleByDefault(false);
        entity.setPersistent(false);
        entity.setGravity(false);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setBillboard(Display.Billboard.CENTER);
    }

    void show(final Plugin plugin, final Player player) {
        if (!pedestal.isValid() || !icon.isValid()) {
            return;
        }
        if (viewers.add(player.getUniqueId())) {
            player.showEntity(plugin, pedestal);
            player.showEntity(plugin, icon);
        }
    }

    void hide(final Plugin plugin, final Player player) {
        if (viewers.remove(player.getUniqueId())) {
            if (!pedestal.isDead()) {
                player.hideEntity(plugin, pedestal);
            }
            if (!icon.isDead()) {
                player.hideEntity(plugin, icon);
            }
        }
    }

    void removeAll(final Plugin plugin) {
        for (final UUID id : new ArrayList<>(viewers)) {
            final Player player = org.bukkit.Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                hide(plugin, player);
            }
        }
        viewers.clear();
        if (!pedestal.isDead()) {
            pedestal.remove();
        }
        if (!icon.isDead()) {
            icon.remove();
        }
    }
}
