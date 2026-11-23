package io.github.near.sweats.perks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerkServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void sweatsPlusCanPickAndPersistAColour() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PerkService perks = new PerkService(folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();
        ranks.assign(id, "Alex", "Sweat+");

        assertTrue(perks.canCustomizePlusColor(id));
        assertTrue(perks.setPlusColor(id, "red"));
        assertEquals(NamedTextColor.RED, perks.plusColor(id));
        assertEquals("red", perks.plusColorName(id).orElseThrow());

        assertFalse(perks.setPlusColor(id, "RED"));

        final PerkService reloaded = new PerkService(folder, ranks, warnings::add);
        assertEquals(NamedTextColor.RED, reloaded.plusColor(id));
    }

    @Test
    void downgradeHidesButKeepsTheSelectionForALaterUpgrade() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PerkService perks = new PerkService(folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();
        ranks.assign(id, "Alex", "Sweat+");
        perks.setPlusColor(id, "aqua");

        ranks.assign(id, "Alex", "Sweat");
        assertFalse(perks.canCustomizePlusColor(id));
        assertNull(perks.plusColor(id));
        assertTrue(perks.plusColorName(id).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> perks.setPlusColor(id, "blue"));

        final PerkService reloaded = new PerkService(folder, ranks, warnings::add);
        assertNull(reloaded.plusColor(id));

        ranks.assign(id, "Alex", "Sweat+");
        assertEquals(NamedTextColor.AQUA, reloaded.plusColor(id));
    }

    @Test
    void onlyTheExactSweatPlusRankQualifies() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PerkService perks = new PerkService(folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();

        ranks.assign(id, "Alex", "Admin");
        assertEquals(PerkTier.SWEAT_PLUS, perks.perks(id).tier());
        assertFalse(perks.canCustomizePlusColor(id));
        assertThrows(IllegalArgumentException.class, () -> perks.setPlusColor(id, "red"));
        assertNull(perks.plusColor(id));
    }

    @Test
    void resetAndInvalidColoursAreHandledWithoutChangingTheSelection() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PerkService perks = new PerkService(folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();
        ranks.assign(id, "Alex", "Sweat+");

        assertFalse(perks.setPlusColor(id, "reset"));

        assertTrue(perks.setPlusColor(id, "gold"));
        assertThrows(IllegalArgumentException.class, () -> perks.setPlusColor(id, "chartreuse"));
        assertEquals(NamedTextColor.GOLD, perks.plusColor(id));

        assertTrue(perks.setPlusColor(id, "reset"));
        assertNull(perks.plusColor(id));
        assertFalse(perks.setPlusColor(id, "reset"));
    }

    @Test
    void changeListenersFireOnlyWhenTheSelectionChanges() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PerkService perks = new PerkService(folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();
        ranks.assign(id, "Alex", "Sweat+");
        final AtomicInteger changes = new AtomicInteger();
        perks.addChangeListener(changed -> changes.incrementAndGet());

        assertTrue(perks.setPlusColor(id, "white"));
        assertEquals(1, changes.get());
        assertFalse(perks.setPlusColor(id, "white"));
        assertEquals(1, changes.get());
        assertTrue(perks.setPlusColor(id, "reset"));
        assertEquals(2, changes.get());
    }

    @Test
    void perksFollowTheCurrentRankImmediately() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PerkService perks = new PerkService(folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();

        assertEquals(2, perks.perks(id).homeSlots());
        assertFalse(perks.perks(id).memberChat());

        ranks.assign(id, "Alex", "Sweat");
        assertEquals(3, perks.perks(id).homeSlots());
        assertTrue(perks.perks(id).memberChat());
        assertTrue(perks.perks(id).whiteChat());

        ranks.assign(id, "Alex", "Default");
        assertEquals(2, perks.perks(id).homeSlots());
        assertFalse(perks.perks(id).memberChat());
    }

    @Test
    void colourNamesAreTheWhitelistedNamedColours() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PerkService perks = new PerkService(folder, ranks, warnings::add);

        assertEquals(16, perks.colorNames().size());
        assertTrue(perks.colorNames().contains("red"));
        assertTrue(perks.colorNames().contains("dark_aqua"));
        assertFalse(perks.colorNames().contains("reset"));
    }
}
