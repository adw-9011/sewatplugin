package io.github.near.sweats.leaderboard;

import java.util.UUID;

/** One row of the playtime leaderboard: who played and for how long (in ticks). */
public record PlaytimeEntry(UUID id, String name, long ticks) {}
