package io.github.near.sweats.perks;

import io.github.near.sweats.rank.Rank;

/**
 * The perk levels. Staff and custom ranks derive one of these from their weight, so a rank
 * added with {@code /rank create} automatically receives the matching perks.
 */
public enum PerkTier {
    DEFAULT,
    GRINDER,
    SWEAT,
    SWEAT_PLUS,
    CUSTOM,
    INVESTOR;

    public static PerkTier of(final Rank rank) {
        return switch (rank.key()) {
            case "grinder" -> GRINDER;
            case "sweat" -> SWEAT;
            case "sweat+" -> SWEAT_PLUS;
            case "custom" -> CUSTOM;
            case "investor" -> INVESTOR;
            case "default" -> DEFAULT;
            default -> fromWeight(rank.weight());
        };
    }

    static PerkTier fromWeight(final int weight) {
        if (weight < 10) {
            return DEFAULT;
        }
        if (weight < 20) {
            return GRINDER;
        }
        if (weight < 30) {
            return SWEAT;
        }
        if (weight < 35) {
            return SWEAT_PLUS;
        }
        if (weight < 38) {
            return CUSTOM;
        }
        if (weight < 40) {
            return INVESTOR;
        }
        // Staff ranks (weight >= 40) get at least Sweat+ perks
        return SWEAT_PLUS;
    }

    public boolean atLeast(final PerkTier other) {
        return ordinal() >= other.ordinal();
    }
}