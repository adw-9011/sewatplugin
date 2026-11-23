package io.github.near.sweats.dupe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DupeRulesTest {

    @Test
    void normalMaterialsMaySplitMergeAndStackToTheirLimit() {
        assertTrue(DupeRules.stack(1, 64, false).isEmpty());
        assertTrue(DupeRules.stack(64, 64, false).isEmpty());
        assertTrue(DupeRules.stack(16, 16, false).isEmpty());
    }

    @Test
    void uniqueItemsMayNeverStack() {
        final DupeRules.Finding finding = DupeRules.stack(2, 64, true).orElseThrow();
        assertEquals(DupeRules.Classification.CONFIRMED, finding.classification());
        assertEquals("stacked-unique", finding.code());
        assertTrue(DupeRules.stack(1, 64, true).isEmpty());
    }

    @Test
    void impossibleAmountsAreConfirmed() {
        assertEquals("invalid-amount",
                DupeRules.stack(0, 64, false).orElseThrow().code());
        assertEquals("invalid-amount",
                DupeRules.stack(-3, 64, false).orElseThrow().code());
        assertEquals("overstacked",
                DupeRules.stack(65, 64, false).orElseThrow().code());
        assertEquals(DupeRules.Classification.CONFIRMED,
                DupeRules.stack(65, 64, false).orElseThrow().classification());
    }

    @Test
    void uniqueIdentityAppearingTwiceIsConfirmed() {
        final DupeRules.Finding finding = DupeRules.duplicateIdentity(
                "3f0d5c2e-aaaa-bbbb-cccc-1234567890ab",
                "inventory[0]", "inventory[9]").orElseThrow();

        assertEquals(DupeRules.Classification.CONFIRMED, finding.classification());
        assertEquals("duplicate-identity", finding.code());
        assertTrue(DupeRules.duplicateIdentity(null, "a", "b").isEmpty());
        assertTrue(DupeRules.duplicateIdentity(" ", "a", "b").isEmpty());
    }

    @Test
    void nestedContainersAreSuspectedNotConfirmed() {
        final DupeRules.Finding finding = DupeRules.nestedContainer(true).orElseThrow();

        assertEquals(DupeRules.Classification.SUSPECTED, finding.classification());
        assertEquals("nested-container", finding.code());
        assertTrue(DupeRules.nestedContainer(false).isEmpty());
    }

    @Test
    void uniqueItemsInsideContainersAreRecordedForReview() {
        final DupeRules.Finding finding = DupeRules.uniqueInContainer(
                "3f0d5c2e-aaaa-bbbb-cccc-1234567890ab", "inventory[4].shulker[2]")
                .orElseThrow();

        assertEquals(DupeRules.Classification.SUSPECTED, finding.classification());
        assertEquals("unique-in-container", finding.code());
        assertTrue(DupeRules.uniqueInContainer(null, "x").isEmpty());
    }

    @Test
    void fingerprintsKeepMaterialAndIdentityDistinct() {
        assertEquals("DIAMOND x64", DupeRules.fingerprint("DIAMOND", 64, null));
        assertEquals("DIAMOND x1 id=abc", DupeRules.fingerprint("DIAMOND", 1, "abc"));
    }
}
