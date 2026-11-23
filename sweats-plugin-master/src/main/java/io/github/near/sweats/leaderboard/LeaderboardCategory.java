package io.github.near.sweats.leaderboard;

import io.github.near.sweats.stats.StatsFormat;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * One of the leaderboards a player can place in the world.
 *
 * <p>Every category has its own theme: a colour, a symbol and a block/item pair used for the
 * decorative pedestal under the floating text. The {@code key} is what operators type in
 * {@code /leaderboard place <key>} and what is saved in {@code leaderboards.yml}.
 */
public enum LeaderboardCategory {

    PLAYTIME("playtime", "Playtime", "playtime", Material.GOLD_BLOCK, Material.CLOCK,
            NamedTextColor.GOLD, "⏱", true),
    DEATHS("deaths", "Deaths", "deaths", Material.RED_NETHER_BRICKS, Material.SKELETON_SKULL,
            NamedTextColor.RED, "☠", false),
    KILLS("kills", "Kills", "kills", Material.NETHERRACK, Material.DIAMOND_SWORD,
            NamedTextColor.DARK_RED, "⚔", false),
    AFK("afk", "AFK Time", "AFK time", Material.LIGHT_BLUE_WOOL, Material.LIGHT_BLUE_BED,
            NamedTextColor.AQUA, "☕", true),
    VOTES("votes", "Total Votes", "votes", Material.EMERALD_BLOCK, Material.EMERALD,
            NamedTextColor.GREEN, "★", false),
    STREAK("streak", "Vote Streak", "best vote streak", Material.AMETHYST_BLOCK,
            Material.BLAZE_ROD, NamedTextColor.LIGHT_PURPLE, "✦", false);

    private final String key;
    private final String title;
    private final String viewerLabel;
    private final Material pedestal;
    private final Material icon;
    private final NamedTextColor color;
    private final String symbol;
    private final boolean timeBased;

    LeaderboardCategory(
            final String key,
            final String title,
            final String viewerLabel,
            final Material pedestal,
            final Material icon,
            final NamedTextColor color,
            final String symbol,
            final boolean timeBased) {
        this.key = key;
        this.title = title;
        this.viewerLabel = viewerLabel;
        this.pedestal = pedestal;
        this.icon = icon;
        this.color = color;
        this.symbol = symbol;
        this.timeBased = timeBased;
    }

    /** Parses a config key or command argument, returning {@code null} when it is unknown. */
    public static LeaderboardCategory fromKey(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final String value = raw.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        for (final LeaderboardCategory category : values()) {
            if (category.key.equalsIgnoreCase(raw.trim()) || category.name().equals(value)) {
                return category;
            }
        }
        return null;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String viewerLabel() {
        return viewerLabel;
    }

    /** A block material, used for the pedestal beneath the text. */
    public Material pedestal() {
        return pedestal;
    }

    /** An item material, used for the floating icon. */
    public Material icon() {
        return icon;
    }

    public NamedTextColor color() {
        return color;
    }

    public String symbol() {
        return symbol;
    }

    /** Whether scores are durations in ticks rather than plain counts. */
    public boolean timeBased() {
        return timeBased;
    }

    public String format(final long value) {
        return timeBased ? StatsFormat.playtime(value) : StatsFormat.count(value);
    }

    @Override
    public String toString() {
        return key;
    }
}
