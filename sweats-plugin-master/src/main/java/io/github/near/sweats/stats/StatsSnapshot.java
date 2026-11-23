package io.github.near.sweats.stats;

import java.util.UUID;

/**
 * Everything {@code /stats} shows for one player, already gathered.
 *
 * <p>Vanilla statistics can only be read while a player is online, so {@link #hasVanillaStats()}
 * says whether those numbers are real or simply placeholders for an offline player.
 *
 * @param uuid the player's id
 * @param name the player's name
 * @param online whether the player is online right now
 * @param hasVanillaStats whether the vanilla counters below were readable
 * @param firstPlayed epoch millis of the first join, or {@code 0} when unknown
 * @param lastPlayed epoch millis of the last join, or {@code 0} when unknown
 * @param playtimeTicks total playtime in ticks
 * @param distanceCm total distance moved, in centimetres
 * @param blocksMined every block broken
 * @param itemsUsed every item use, which includes placing blocks
 * @param blocksPlaced every block placed, tracked by this plugin
 * @param deaths deaths
 * @param playerKills players killed
 * @param mobKills mobs killed
 * @param world current world, or {@code null} when offline
 * @param x current block x, or {@code null} when offline
 * @param y current block y, or {@code null} when offline
 * @param z current block z, or {@code null} when offline
 * @param ping current ping in milliseconds
 */
public record StatsSnapshot(
        UUID uuid,
        String name,
        boolean online,
        boolean hasVanillaStats,
        long firstPlayed,
        long lastPlayed,
        long playtimeTicks,
        long distanceCm,
        long blocksMined,
        long itemsUsed,
        long blocksPlaced,
        int deaths,
        int playerKills,
        int mobKills,
        String world,
        Integer x,
        Integer y,
        Integer z,
        int ping) {

    /** Players killed plus mobs killed. */
    public long totalKills() {
        return (long) playerKills + mobKills;
    }

    /** Kills per death, or {@code 0} when there are no deaths yet. */
    public double killDeathRatio() {
        return deaths == 0 ? 0.0D : totalKills() / (double) deaths;
    }
}
