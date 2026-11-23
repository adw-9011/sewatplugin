package io.github.near.sweats.teleport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Shared safe-destination checks for homes and teleport requests. */
public final class TeleportSafety {

    private static final int[][] OFFSETS = {
        {0, 0}, {0, 1}, {1, 0}, {-1, 0}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final Set<String> blockedWorlds;

    public TeleportSafety(final Set<String> blockedWorlds) {
        final Set<String> normalized = new LinkedHashSet<>();
        for (final String world : blockedWorlds) {
            if (world != null && !world.isBlank()) {
                normalized.add(world.toLowerCase(Locale.ROOT));
            }
        }
        this.blockedWorlds = Set.copyOf(normalized);
    }

    public static TeleportSafety of(final List<String> blockedWorlds) {
        return new TeleportSafety(new LinkedHashSet<>(blockedWorlds));
    }

    public boolean isBlocked(final World world) {
        return world == null || isBlocked(world.getName());
    }

    public boolean isBlocked(final String worldName) {
        return worldName == null || blockedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    /** A nearby location with room for a player, or {@code null} when no safe spot exists. */
    public Location safe(final Location target) {
        if (target == null || target.getWorld() == null || isBlocked(target.getWorld())) {
            return null;
        }
        final World world = target.getWorld();
        if (!world.getWorldBorder().isInside(target)) {
            return null;
        }

        for (int vertical = 0; vertical <= 3; vertical++) {
            for (final int[] offset : OFFSETS) {
                final int xOffset = offset[0];
                final int zOffset = offset[1];
                for (final int direction : vertical == 0 ? new int[] {0} : new int[] {1, -1}) {
                    final Location candidate = new Location(
                            world,
                            target.getBlockX() + xOffset + 0.5,
                            target.getY() + vertical * direction,
                            target.getBlockZ() + zOffset + 0.5,
                            target.getYaw(),
                            target.getPitch());
                    if (candidate.getY() < world.getMinHeight() || candidate.getY() > world.getMaxHeight()) {
                        continue;
                    }
                    if (isSafe(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isSafe(final Location location) {
        final World world = location.getWorld();
        if (world == null) {
            return false;
        }
        final Block feet = world.getBlockAt(location);
        final Block head = feet.getRelative(0, 1, 0);
        final Block below = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && below.getType().isSolid();
    }
}
