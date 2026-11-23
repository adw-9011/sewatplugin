package io.github.near.sweats.perks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.rank.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class RankPerksTest {

    private static Rank rank(final String name, final int weight) {
        return new Rank(name, Component.text(name, NamedTextColor.GRAY), weight, true);
    }

    @Test
    void coreRanksUseTheExactPerkMatrix() {
        final RankPerks defaultPerks = RankPerks.of(rank("Default", 0));
        assertEquals(PerkTier.DEFAULT, defaultPerks.tier());
        assertEquals(2, defaultPerks.homeSlots());
        assertEquals(RankPerks.EnderChestSize.NORMAL, defaultPerks.enderChest());
        assertEquals(2, defaultPerks.emojiLimit());
        assertFalse(defaultPerks.emojiColor());
        assertFalse(defaultPerks.whiteChat());
        assertTrue(defaultPerks.baseTeamLimit());
        assertFalse(defaultPerks.memberChat());
        assertFalse(defaultPerks.betaAccess());
        assertFalse(defaultPerks.plusColor());
        assertFalse(defaultPerks.showAccess());
        assertFalse(defaultPerks.statsAccess());
        assertFalse(defaultPerks.nickAccess());
        assertFalse(defaultPerks.scareAccess());
        assertFalse(defaultPerks.gifAccess());
        assertFalse(defaultPerks.customRankName());
        assertFalse(defaultPerks.higherTabPriority());
        assertFalse(defaultPerks.highestNonStaffTabPriority());

        final RankPerks grinderPerks = RankPerks.of(rank("Grinder", 10));
        assertEquals(PerkTier.GRINDER, grinderPerks.tier());
        assertEquals(2, grinderPerks.homeSlots());
        assertEquals(RankPerks.EnderChestSize.NORMAL, grinderPerks.enderChest());
        assertEquals(8, grinderPerks.emojiLimit());
        assertFalse(grinderPerks.emojiColor());
        assertTrue(grinderPerks.whiteChat());
        assertTrue(grinderPerks.baseTeamLimit());
        assertFalse(grinderPerks.memberChat());
        assertFalse(grinderPerks.betaAccess());
        assertFalse(grinderPerks.plusColor());
        assertTrue(grinderPerks.showAccess());
        assertTrue(grinderPerks.statsAccess());
        assertFalse(grinderPerks.nickAccess());
        assertFalse(grinderPerks.scareAccess());
        assertFalse(grinderPerks.gifAccess());
        assertFalse(grinderPerks.customRankName());
        assertFalse(grinderPerks.higherTabPriority());
        assertFalse(grinderPerks.highestNonStaffTabPriority());

        final RankPerks sweatPerks = RankPerks.of(rank("Sweat", 20));
        assertEquals(PerkTier.SWEAT, sweatPerks.tier());
        assertEquals(3, sweatPerks.homeSlots());
        assertEquals(RankPerks.EnderChestSize.SMALL, sweatPerks.enderChest());
        assertEquals(16, sweatPerks.emojiLimit());
        assertFalse(sweatPerks.emojiColor());
        assertTrue(sweatPerks.whiteChat());
        assertEquals(6, sweatPerks.teamLimit());
        assertFalse(sweatPerks.baseTeamLimit());
        assertTrue(sweatPerks.memberChat());
        assertTrue(sweatPerks.betaAccess());
        assertFalse(sweatPerks.plusColor());
        assertTrue(sweatPerks.showAccess());
        assertTrue(sweatPerks.statsAccess());
        assertTrue(sweatPerks.nickAccess());
        assertFalse(sweatPerks.scareAccess());
        assertFalse(sweatPerks.gifAccess());
        assertFalse(sweatPerks.customRankName());
        assertFalse(sweatPerks.higherTabPriority());
        assertFalse(sweatPerks.highestNonStaffTabPriority());

        final RankPerks sweatPlusPerks = RankPerks.of(rank("Sweat+", 30));
        assertEquals(PerkTier.SWEAT_PLUS, sweatPlusPerks.tier());
        assertEquals(4, sweatPlusPerks.homeSlots());
        assertEquals(RankPerks.EnderChestSize.MEDIUM, sweatPlusPerks.enderChest());
        assertTrue(sweatPlusPerks.allEmojis());
        assertFalse(sweatPlusPerks.emojiColor());
        assertTrue(sweatPlusPerks.whiteChat());
        assertEquals(10, sweatPlusPerks.teamLimit());
        assertTrue(sweatPlusPerks.memberChat());
        assertTrue(sweatPlusPerks.betaAccess());
        assertTrue(sweatPlusPerks.plusColor());
        assertTrue(sweatPlusPerks.showAccess());
        assertTrue(sweatPlusPerks.statsAccess());
        assertTrue(sweatPlusPerks.nickAccess());
        assertTrue(sweatPlusPerks.scareAccess());
        assertTrue(sweatPlusPerks.gifAccess());
        assertFalse(sweatPlusPerks.customRankName());
        assertFalse(sweatPlusPerks.higherTabPriority());
        assertFalse(sweatPlusPerks.highestNonStaffTabPriority());

        final RankPerks customPerks = RankPerks.of(rank("Custom", 35));
        assertEquals(PerkTier.CUSTOM, customPerks.tier());
        assertEquals(5, customPerks.homeSlots());
        assertEquals(RankPerks.EnderChestSize.MEDIUM, customPerks.enderChest());
        assertTrue(customPerks.allEmojis());
        assertFalse(customPerks.emojiColor());
        assertTrue(customPerks.whiteChat());
        assertEquals(15, customPerks.teamLimit());
        assertTrue(customPerks.memberChat());
        assertTrue(customPerks.betaAccess());
        assertFalse(customPerks.plusColor());
        assertTrue(customPerks.showAccess());
        assertTrue(customPerks.statsAccess());
        assertTrue(customPerks.nickAccess());
        assertTrue(customPerks.scareAccess());
        assertTrue(customPerks.gifAccess());
        assertTrue(customPerks.customRankName());
        assertTrue(customPerks.higherTabPriority());
        assertFalse(customPerks.highestNonStaffTabPriority());

        final RankPerks investorPerks = RankPerks.of(rank("Investor", 38));
        assertEquals(PerkTier.INVESTOR, investorPerks.tier());
        assertEquals(7, investorPerks.homeSlots());
        assertEquals(RankPerks.EnderChestSize.MEDIUM, investorPerks.enderChest());
        assertTrue(investorPerks.allEmojis());
        assertFalse(investorPerks.emojiColor());
        assertTrue(investorPerks.whiteChat());
        assertEquals(20, investorPerks.teamLimit());
        assertTrue(investorPerks.memberChat());
        assertTrue(investorPerks.betaAccess());
        assertFalse(investorPerks.plusColor());
        assertTrue(investorPerks.showAccess());
        assertTrue(investorPerks.statsAccess());
        assertTrue(investorPerks.nickAccess());
        assertTrue(investorPerks.scareAccess());
        assertTrue(investorPerks.gifAccess());
        assertTrue(investorPerks.customRankName());
        assertTrue(investorPerks.higherTabPriority());
        assertTrue(investorPerks.highestNonStaffTabPriority());
    }

    @Test
    void exactCoreKeysWinOverWeights() {
        assertEquals(PerkTier.GRINDER, RankPerks.of(rank("Grinder", 0)).tier());
        assertEquals(PerkTier.SWEAT, RankPerks.of(rank("Sweat", 0)).tier());
        assertEquals(PerkTier.SWEAT_PLUS, RankPerks.of(rank("Sweat+", 0)).tier());
        assertEquals(PerkTier.CUSTOM, RankPerks.of(rank("Custom", 0)).tier());
        assertEquals(PerkTier.INVESTOR, RankPerks.of(rank("Investor", 0)).tier());
        assertEquals(PerkTier.DEFAULT, RankPerks.of(rank("Default", 60)).tier());
    }

    @Test
    void customRanksDeriveTheirTierFromWeight() {
        assertEquals(PerkTier.DEFAULT, RankPerks.of(rank("Helper", 9)).tier());
        assertEquals(PerkTier.GRINDER, RankPerks.of(rank("Helper", 10)).tier());
        assertEquals(PerkTier.GRINDER, RankPerks.of(rank("Helper", 19)).tier());
        assertEquals(PerkTier.SWEAT, RankPerks.of(rank("Helper", 20)).tier());
        assertEquals(PerkTier.SWEAT, RankPerks.of(rank("Helper", 29)).tier());
        assertEquals(PerkTier.SWEAT_PLUS, RankPerks.of(rank("Helper", 30)).tier());
        assertEquals(PerkTier.SWEAT_PLUS, RankPerks.of(rank("Helper", 34)).tier());
        assertEquals(PerkTier.CUSTOM, RankPerks.of(rank("Helper", 35)).tier());
        assertEquals(PerkTier.CUSTOM, RankPerks.of(rank("Helper", 37)).tier());
        assertEquals(PerkTier.INVESTOR, RankPerks.of(rank("Helper", 38)).tier());
        assertEquals(PerkTier.INVESTOR, RankPerks.of(rank("Helper", 39)).tier());
        assertEquals(PerkTier.SWEAT_PLUS, RankPerks.of(rank("Helper", 59)).tier());
    }

    @Test
    void staffTiersGetEveryPerkExceptTheExactSweatPlusColor() {
        final RankPerks admin = RankPerks.of(rank("Admin", 40));
        assertEquals(PerkTier.SWEAT_PLUS, admin.tier());
        assertEquals(10, admin.teamLimit());
        assertTrue(admin.memberChat());
        assertTrue(admin.betaAccess());
        assertFalse(admin.plusColor());
        assertTrue(admin.showAccess());
        assertTrue(admin.statsAccess());
        assertTrue(admin.nickAccess());
        assertTrue(admin.scareAccess());
        assertTrue(admin.gifAccess());
        assertFalse(admin.customRankName());
        assertFalse(admin.higherTabPriority());
        assertFalse(admin.highestNonStaffTabPriority());

        final RankPerks dev = RankPerks.of(rank("DEV", 60));
        assertEquals(PerkTier.SWEAT_PLUS, dev.tier());
        assertFalse(dev.plusColor());
        assertFalse(dev.highestNonStaffTabPriority());
    }

    @Test
    void rejectsNullRanks() {
        assertThrows(NullPointerException.class, () -> RankPerks.of(null));
    }
}