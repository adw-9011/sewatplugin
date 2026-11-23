package io.github.near.sweats.display;

import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.stats.PlayerProfile;
import io.github.near.sweats.stats.StatsFormat;
import net.kyori.adventure.text.format.TextColor;

record DisplaySnapshot(
        Rank rank,
        TextColor plusColor,
        String team,
        String coin,
        String shards,
        String playtime,
        String online) {

    static DisplaySnapshot of(
            final PlayerProfile profile,
            final Rank rank,
            final String team,
            final long coins,
            final long shards,
            final boolean currencyLoaded,
            final int visibleOnline,
            final int maxPlayers) {
        return of(profile, rank, null, team, coins, shards, currencyLoaded, visibleOnline, maxPlayers);
    }

    static DisplaySnapshot of(
            final PlayerProfile profile,
            final Rank rank,
            final TextColor plusColor,
            final String team,
            final long coins,
            final long shards,
            final boolean currencyLoaded,
            final int visibleOnline,
            final int maxPlayers) {
        return new DisplaySnapshot(
                rank,
                plusColor,
                team,
                currencyLoaded ? StatsFormat.count(coins) : "…",
                currencyLoaded ? StatsFormat.count(shards) : "…",
                StatsFormat.playtime(profile.playtimeTicks()),
                Math.max(0, visibleOnline) + "/" + Math.max(0, maxPlayers));
    }
}
