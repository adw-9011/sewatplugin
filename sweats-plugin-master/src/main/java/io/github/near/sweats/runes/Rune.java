package io.github.near.sweats.runes;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/** One cosmetic rune from the SweatyRunes catalog. */
public record Rune(
        String id,
        String name,
        Group group,
        Equip equip,
        Rarity rarity,
        int level,
        String hash,
        String desc,
        RuneFx.Effect effect) {

    public enum Group {
        STANDARD("Standard", Material.PRISMARINE_CRYSTALS),
        SLAYER("Slayer", Material.ECHO_SHARD),
        EVENT("Event", Material.GLOW_INK_SAC),
        FIRE_SALE("Fire Sale", Material.NETHER_STAR);

        public final String display;
        public final Material fallback;

        Group(final String display, final Material fallback) {
            this.display = display;
            this.fallback = fallback;
        }
    }

    public enum Rarity {
        COMMON(NamedTextColor.WHITE),
        UNCOMMON(NamedTextColor.GREEN),
        RARE(NamedTextColor.BLUE),
        EPIC(NamedTextColor.DARK_PURPLE),
        LEGENDARY(NamedTextColor.GOLD);

        public final TextColor color;

        Rarity(final TextColor color) {
            this.color = color;
        }
    }

    public enum Equip {
        WEAPON("Weapons"),
        BOW("Bows"),
        AXE("Axes"),
        BOOTS("Boots"),
        CHESTPLATE("Chestplates"),
        HELMET("Helmets"),
        HOE("Hoes");

        public final String display;

        Equip(final String display) {
            this.display = display;
        }

        public boolean matches(final Material material) {
            final String name = material.name();
            return switch (this) {
                case WEAPON -> name.endsWith("_SWORD")
                        || name.endsWith("_SPEAR")
                        || material == Material.TRIDENT
                        || material == Material.MACE;
                case BOW -> material == Material.BOW || material == Material.CROSSBOW;
                case AXE -> name.endsWith("_AXE");
                case BOOTS -> name.endsWith("_BOOTS");
                case CHESTPLATE -> name.endsWith("_CHESTPLATE") || material == Material.ELYTRA;
                case HELMET -> name.endsWith("_HELMET");
                case HOE -> name.endsWith("_HOE");
            };
        }

        /** Runes that trigger when the player hits something with this item. */
        public boolean melee() {
            return this == WEAPON || this == AXE || this == HOE;
        }
    }
}
