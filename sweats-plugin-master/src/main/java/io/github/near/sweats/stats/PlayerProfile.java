package io.github.near.sweats.stats;

/**
 * The lifetime counters shown on the scoreboard, read from the same vanilla statistics as
 * {@code /stats}. Kills only count players killed; mob kills are intentionally excluded.
 */
public record PlayerProfile(long playtimeTicks, int kills, int deaths) {}
