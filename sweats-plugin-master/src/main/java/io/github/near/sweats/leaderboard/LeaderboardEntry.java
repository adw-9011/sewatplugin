package io.github.near.sweats.leaderboard;

import java.util.UUID;

/** One ranked row: a real player and their score for a particular leaderboard category. */
public record LeaderboardEntry(UUID id, String name, long value) {}
