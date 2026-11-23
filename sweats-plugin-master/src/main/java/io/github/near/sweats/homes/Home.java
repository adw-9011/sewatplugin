package io.github.near.sweats.homes;

/** One saved home. Identity is the owner UUID plus the slot. */
public record Home(
        int slot,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {

    public Home withSlot(final int newSlot) {
        return new Home(newSlot, world, x, y, z, yaw, pitch);
    }
}
