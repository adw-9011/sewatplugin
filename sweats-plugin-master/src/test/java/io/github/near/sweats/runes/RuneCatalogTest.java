package io.github.near.sweats.runes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class RuneCatalogTest {

    private static final Pattern TEXTURE_HASH = Pattern.compile("[0-9a-f]{64}");

    @Test
    void catalogHasSixtyStableIds() {
        assertEquals(60, RuneCatalog.all().size());

        final Set<String> ids = new HashSet<>();
        for (final Rune rune : RuneCatalog.all()) {
            assertTrue(ids.add(rune.id()), "duplicate rune id " + rune.id());
            assertEquals(rune.id().toLowerCase(java.util.Locale.ROOT), rune.id());
            assertFalse(rune.name().isBlank());
            assertFalse(rune.desc().isBlank());
            assertNotNull(rune.group());
            assertNotNull(rune.equip());
            assertNotNull(rune.rarity());
            assertNotNull(rune.effect());
        }
    }

    @Test
    void lookupIsCaseInsensitiveAndUnknownIdsReturnNull() {
        final Rune blood = RuneCatalog.get("blood");
        assertEquals(blood, RuneCatalog.get("BLOOD"));
        assertEquals(blood, RuneCatalog.get("Blood"));
        assertNull(RuneCatalog.get("not_a_rune"));
        assertNull(RuneCatalog.get(null));
    }

    @Test
    void textureHashesAreEitherAbsentOrValidSha1() {
        for (final Rune rune : RuneCatalog.all()) {
            if (rune.hash() == null) {
                continue;
            }
            assertTrue(
                    TEXTURE_HASH.matcher(rune.hash()).matches(),
                    rune.id() + " has an invalid texture hash");
        }
    }

    @Test
    void equipmentMatchingCoversTheSameItemsAsTheAddon() {
        assertTrue(Rune.Equip.WEAPON.matches(Material.DIAMOND_SWORD));
        assertTrue(Rune.Equip.WEAPON.matches(Material.NETHERITE_SPEAR));
        assertTrue(Rune.Equip.WEAPON.matches(Material.TRIDENT));
        assertTrue(Rune.Equip.WEAPON.matches(Material.MACE));
        assertFalse(Rune.Equip.WEAPON.matches(Material.BOW));

        assertTrue(Rune.Equip.BOW.matches(Material.BOW));
        assertTrue(Rune.Equip.BOW.matches(Material.CROSSBOW));
        assertFalse(Rune.Equip.BOW.matches(Material.ARROW));

        assertTrue(Rune.Equip.AXE.matches(Material.DIAMOND_AXE));
        assertTrue(Rune.Equip.BOOTS.matches(Material.NETHERITE_BOOTS));
        assertTrue(Rune.Equip.CHESTPLATE.matches(Material.DIAMOND_CHESTPLATE));
        assertTrue(Rune.Equip.CHESTPLATE.matches(Material.ELYTRA));
        assertTrue(Rune.Equip.HELMET.matches(Material.DIAMOND_HELMET));
        assertTrue(Rune.Equip.HOE.matches(Material.DIAMOND_HOE));

        assertTrue(Rune.Equip.WEAPON.melee());
        assertTrue(Rune.Equip.AXE.melee());
        assertTrue(Rune.Equip.HOE.melee());
        assertFalse(Rune.Equip.BOW.melee());
        assertFalse(Rune.Equip.BOOTS.melee());
        assertFalse(Rune.Equip.CHESTPLATE.melee());
        assertFalse(Rune.Equip.HELMET.melee());
    }

    @Test
    void knownRunesKeepTheirGroupEquipAndRarity() {
        final Rune blood = RuneCatalog.get("blood");
        assertEquals("Blood", blood.name());
        assertEquals(Rune.Group.STANDARD, blood.group());
        assertEquals(Rune.Equip.WEAPON, blood.equip());
        assertEquals(Rune.Rarity.COMMON, blood.rarity());
        assertEquals(1, blood.level());

        final Rune darkness = RuneCatalog.get("darkness_within");
        assertEquals(Rune.Group.SLAYER, darkness.group());
        assertEquals(Rune.Equip.BOOTS, darkness.equip());
        assertEquals(Rune.Rarity.LEGENDARY, darkness.rarity());

        final Rune spellbound = RuneCatalog.get("spellbound");
        assertEquals(Rune.Group.FIRE_SALE, spellbound.group());
        assertEquals(Rune.Equip.HELMET, spellbound.equip());
        assertEquals(Rune.Rarity.LEGENDARY, spellbound.rarity());

        final Rune jerry = RuneCatalog.get("jerry");
        assertEquals(Rune.Group.EVENT, jerry.group());
        assertEquals(Rune.Equip.WEAPON, jerry.equip());
    }
}
