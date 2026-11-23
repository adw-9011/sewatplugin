package io.github.near.sweats.homes;

/**
 * Pure countdown state. Keeping the movement/expiry rules free of Bukkit makes the cancellation
 * behaviour easy to test.
 */
public final class TeleportWarmup {

    private static final double MOVE_EPSILON_SQUARED = 0.04D;

    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final long startedAt;
    private final long durationMillis;

    public TeleportWarmup(
            final String world,
            final double x,
            final double y,
            final double z,
            final long startedAt,
            final long durationMillis) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.startedAt = startedAt;
        this.durationMillis = Math.max(0L, durationMillis);
    }

    public boolean moved(final String currentWorld, final double currentX, final double currentY, final double currentZ) {
        if (world == null ? currentWorld != null : !world.equals(currentWorld)) {
            return true;
        }
        final double dx = currentX - x;
        final double dy = currentY - y;
        final double dz = currentZ - z;
        return dx * dx + dy * dy + dz * dz > MOVE_EPSILON_SQUARED;
    }

    public boolean finished(final long now) {
        return now >= startedAt + durationMillis;
    }

    /** Whole seconds left, rounded up, so the player sees 5, 4, 3, 2, 1. */
    public int secondsRemaining(final long now) {
        final long remaining = startedAt + durationMillis - now;
        if (remaining <= 0L) {
            return 0;
        }
        return (int) ((remaining + 999L) / 1000L);
    }
}
