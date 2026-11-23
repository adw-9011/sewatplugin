package io.github.near.sweats.perks;

import io.github.near.sweats.rank.Rank;
import java.util.Objects;

/**
 * The effective perks of one rank. Everything is derived from the rank on demand, so a rank
 * change, reload or reconnect can never leave a stale grant behind and never has to delete data.
 */
public record RankPerks(
        PerkTier tier,
        int homeSlots,
        EnderChestSize enderChest,
        int emojiLimit,
        boolean emojiColor,
        boolean whiteChat,
        int teamLimit,
        boolean memberChat,
        boolean betaAccess,
        boolean plusColor,
        boolean showAccess,
        boolean statsAccess,
        boolean nickAccess,
        boolean scareAccess,
        boolean gifAccess,
        boolean customRankName,
        boolean higherTabPriority,
        boolean highestNonStaffTabPriority) {

    public enum EnderChestSize {
        NORMAL(27),
        SMALL(36),
        MEDIUM(54);

        private final int slots;

        EnderChestSize(final int slots) {
            this.slots = slots;
        }

        public int slots() {
            return slots;
        }
    }

    public static RankPerks of(final Rank rank) {
        Objects.requireNonNull(rank, "rank");
        final PerkTier tier = PerkTier.of(rank);
        return switch (tier) {
            case DEFAULT -> new RankPerks(
                    tier, 2, EnderChestSize.NORMAL, 2, false, false, -1, false, false, false,
                    false, false, false, false, false, false, false, false);
            case GRINDER -> new RankPerks(
                    tier, 2, EnderChestSize.NORMAL, 8, false, true, -1, false, false, false,
                    true, true, false, false, false, false, false, false);
            case SWEAT -> new RankPerks(
                    tier, 3, EnderChestSize.SMALL, 16, false, true, 6, true, true, false,
                    true, true, true, false, false, false, false, false);
            case SWEAT_PLUS -> new RankPerks(
                    tier,
                    4,
                    EnderChestSize.MEDIUM,
                    -1,
                    false,
                    true,
                    10,
                    true,
                    true,
                    "sweat+".equals(rank.key()),
                    true, true, true, true, true, false, false, false);
            case CUSTOM -> new RankPerks(
                    tier,
                    5,
                    EnderChestSize.MEDIUM,
                    -1,
                    false,
                    true,
                    15,
                    true,
                    true,
                    false,
                    true, true, true, true, true, true, true, false);
            case INVESTOR -> new RankPerks(
                    tier,
                    7,
                    EnderChestSize.MEDIUM,
                    -1,
                    false,
                    true,
                    20,
                    true,
                    true,
                    false,
                    true, true, true, true, true, true, true, true);
        };
    }

    /**
     * Returns the home teleport cooldown in seconds for this rank's perk tier.
     * Only applies after a successful teleport.
     */
    public int homeCooldownSeconds() {
        return switch (tier) {
            case DEFAULT -> 300; // 5 minutes
            case GRINDER -> 120; // 2 minutes
            case SWEAT -> 60;    // 1 minute
            case SWEAT_PLUS, CUSTOM, INVESTOR -> 30; // 30 seconds
        };
    }

    /**
     * Returns the TPA request cooldown in seconds for this rank's perk tier.
     * Only applies when a request is successfully created and sent.
     */
    public int tpaCooldownSeconds() {
        return switch (tier) {
            case DEFAULT, GRINDER -> 60; // 1 minute
            case SWEAT, SWEAT_PLUS, CUSTOM, INVESTOR -> 30; // 30 seconds
        };
    }

    /** {@code -1} means every code in the {@code Emojis} registry is allowed. */
    public boolean allEmojis() {
        return emojiLimit < 0;
    }

    /** {@code -1} means the base team size from config.yml applies. */
    public boolean baseTeamLimit() {
        return teamLimit < 0;
    }
}