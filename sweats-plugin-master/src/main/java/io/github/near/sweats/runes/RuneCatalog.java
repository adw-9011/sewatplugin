package io.github.near.sweats.runes;

import static io.github.near.sweats.runes.Rune.Equip.AXE;
import static io.github.near.sweats.runes.Rune.Equip.BOOTS;
import static io.github.near.sweats.runes.Rune.Equip.BOW;
import static io.github.near.sweats.runes.Rune.Equip.CHESTPLATE;
import static io.github.near.sweats.runes.Rune.Equip.HELMET;
import static io.github.near.sweats.runes.Rune.Equip.HOE;
import static io.github.near.sweats.runes.Rune.Equip.WEAPON;
import static io.github.near.sweats.runes.Rune.Group.EVENT;
import static io.github.near.sweats.runes.Rune.Group.FIRE_SALE;
import static io.github.near.sweats.runes.Rune.Group.SLAYER;
import static io.github.near.sweats.runes.Rune.Group.STANDARD;
import static io.github.near.sweats.runes.Rune.Rarity.COMMON;
import static io.github.near.sweats.runes.Rune.Rarity.EPIC;
import static io.github.near.sweats.runes.Rune.Rarity.LEGENDARY;
import static io.github.near.sweats.runes.Rune.Rarity.RARE;
import static io.github.near.sweats.runes.Rune.Rarity.UNCOMMON;
import static io.github.near.sweats.runes.RuneFx.above;
import static io.github.near.sweats.runes.RuneFx.antlers;
import static io.github.near.sweats.runes.RuneFx.block;
import static io.github.near.sweats.runes.RuneFx.combo;
import static io.github.near.sweats.runes.RuneFx.crown;
import static io.github.near.sweats.runes.RuneFx.dust;
import static io.github.near.sweats.runes.RuneFx.enchantIn;
import static io.github.near.sweats.runes.RuneFx.fan;
import static io.github.near.sweats.runes.RuneFx.item;
import static io.github.near.sweats.runes.RuneFx.note;
import static io.github.near.sweats.runes.RuneFx.orbit;
import static io.github.near.sweats.runes.RuneFx.orbitBlock;
import static io.github.near.sweats.runes.RuneFx.orbitItem;
import static io.github.near.sweats.runes.RuneFx.puff;
import static io.github.near.sweats.runes.RuneFx.rainbow;
import static io.github.near.sweats.runes.RuneFx.sound;
import static io.github.near.sweats.runes.RuneFx.spiral;
import static io.github.near.sweats.runes.RuneFx.wave;
import static io.github.near.sweats.runes.RuneFx.zigzag;
import static io.github.near.sweats.runes.RuneFx.zigzagDust;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Particle;

/** The fixed SweatyRunes catalog. IDs are stable and used by items and persistent data. */
public final class RuneCatalog {

    private static final List<Rune> ALL = new ArrayList<>();
    private static final Map<String, Rune> BY_ID = new HashMap<>();

    private RuneCatalog() {}

    public static Rune get(final String id) {
        return id == null ? null : BY_ID.get(id.toLowerCase(Locale.ROOT));
    }

    public static List<Rune> all() {
        return List.copyOf(ALL);
    }

    private static void add(final Rune rune) {
        ALL.add(rune);
        BY_ID.put(rune.id(), rune);
    }

    private static void standard(
            final String id,
            final String name,
            final int level,
            final Rune.Equip equip,
            final Rune.Rarity rarity,
            final String hash,
            final String description,
            final RuneFx.Effect effect) {
        add(new Rune(id, name, STANDARD, equip, rarity, level, hash, description, effect));
    }

    private static void slayer(
            final String id,
            final String name,
            final Rune.Equip equip,
            final Rune.Rarity rarity,
            final String hash,
            final String description,
            final RuneFx.Effect effect) {
        add(new Rune(id, name, SLAYER, equip, rarity, 0, hash, description, effect));
    }

    private static void event(
            final String id,
            final String name,
            final Rune.Equip equip,
            final String hash,
            final String description,
            final RuneFx.Effect effect) {
        add(new Rune(id, name, EVENT, equip, EPIC, 0, hash, description, effect));
    }

    private static void fireSale(
            final String id,
            final String name,
            final Rune.Equip equip,
            final String description,
            final RuneFx.Effect effect) {
        add(new Rune(id, name, FIRE_SALE, equip, LEGENDARY, 0, null, description, effect));
    }

    static {
        // ------------------------------------------------------------------ Standard
        standard(
                "blood", "Blood", 1, WEAPON, COMMON,
                "e02677053dc54245dac4b399d14aae21ee71a010bd9c336c8ecee1a0dbe8f58b",
                "Blood splatter",
                combo(block(Material.REDSTONE_BLOCK, 10, 0.3), dust(170, 0, 0, 1.2f, 6, 0.3)));
        standard(
                "snow", "Snow", 2, WEAPON, COMMON, null,
                "Snow particle explosion", puff(Particle.SNOWFLAKE, 12, 0.5, 0.12));
        standard(
                "lava", "Lava", 3, BOW, COMMON,
                "b13d903f601034ac3400d2625fef104e9b0940746c554193f6d9e85a84a966a1",
                "Lava particles", puff(Particle.LAVA, 1, 0.1, 0));
        standard(
                "fading_white", "Fading White", 3, AXE, COMMON, null,
                "White smoke", puff(Particle.CLOUD, 5, 0.4, 0.03));
        standard(
                "gem", "Gem", 4, BOW, COMMON,
                "43a1ad4fcc42fb63c681328e42d63c83ca193b333af2a426728a25a8cc600692",
                "Green stars", puff(Particle.HAPPY_VILLAGER, 2, 0.15, 0));
        standard(
                "zap", "Zap", 5, BOOTS, UNCOMMON,
                "ed0947c40de6789f6cfa2370add2a04c9855e45fde9483d655101e9510288ee8",
                "Yellow dust", dust(255, 230, 40, 1.2f, 3, 0.25));
        standard(
                "slimy", "Slimy", 5, BOW, COMMON, null,
                "Slime particles", puff(Particle.ITEM_SLIME, 2, 0.12, 0));
        standard(
                "fading_green", "Fading Green", 5, AXE, UNCOMMON, null,
                "Green smoke",
                combo(dust(60, 200, 60, 1.6f, 4, 0.4), puff(Particle.CLOUD, 1, 0.3, 0.02)));
        standard(
                "white_spiral", "White Spiral", 6, BOW, UNCOMMON,
                "3ef2432ef305361384d4318df5bda5bd1ac2d9bea06d1f5cfead6dd87e37ddf5",
                "Firework spiral", spiral(Particle.FIREWORK, 0.35, 0.0));
        standard(
                "smokey", "Smokey", 7, BOW, COMMON,
                "e4d8a8d527f65a4f434f894f7ee42eb843015bda7927c63c6ea8a754afe9bb1b",
                "Smoke particles", puff(Particle.SMOKE, 3, 0.1, 0.01));
        standard(
                "hot", "Hot", 8, BOOTS, UNCOMMON,
                "26660b016d05645fffd1f48b792d1abe5d8f30dc96567569ae1d982d250b693c",
                "Lava particles", puff(Particle.LAVA, 1, 0.3, 0));
        standard(
                "redstone", "Redstone", 9, BOOTS, RARE,
                "87a7a894057d4a1ff22a161d76600f719da57916633f683808cf4d358bb73a21",
                "Redstone particles", dust(255, 0, 0, 1.2f, 3, 0.3));
        standard(
                "hearts", "Hearts", 10, WEAPON, RARE,
                "2c1c179ad51955f1522c48ea9931f09c162741b45e22e9d3feb682c7e5ed8274",
                "Heart particles", puff(Particle.HEART, 3, 0.4, 0));
        standard(
                "sparkling", "Sparkling", 11, BOOTS, RARE,
                "f1e2428cb359988f4c4ff0e61de21385c62269de19a69762d773223b75dd1666",
                "Firework trail", puff(Particle.FIREWORK, 2, 0.2, 0.02));
        standard(
                "magical", "Magical", 12, BOW, RARE,
                "24480e39ea63e347d268de83090d09984bf34394118848348bf4eb57490ce9d2",
                "Witch particles", puff(Particle.WITCH, 3, 0.15, 0));
        standard(
                "fire_spiral", "Fire Spiral", 13, BOW, RARE,
                "8301aa86cafd4b2d732a9b4894cfcfc65edc828e8571b45dbf0a3ba96575cccf",
                "Flame spiral", spiral(Particle.FLAME, 0.35, 0.0));
        standard(
                "golden", "Golden", 14, BOW, EPIC,
                "35f4861aa5b22ee28a90e75dab45d221efd14c0b1ecc8ee998fb67e43bb8f3de",
                "Gold items appear behind arrows", item(Material.GOLD_INGOT, 1, 0.1));
        standard(
                "music", "Music", 15, WEAPON, EPIC,
                "3b481c31dc683bdcb7d375a7c5db7ac7adf9e9fe8b6c04a64931613e29fe470e",
                "Music notes and sound", note());
        standard(
                "clouds", "Clouds", 16, BOOTS, EPIC,
                "2273740d454de962484712f9835e35119b37ab867fa6982d5cc1f333c2334e59",
                "White smoke", puff(Particle.CLOUD, 3, 0.25, 0.01));
        standard(
                "wake", "Wake", 18, BOW, LEGENDARY,
                "977c1fc93216e96d435cf962e1173de8d1a249b644894d72676eba732fcd56e7",
                "Water droplets behind arrows", puff(Particle.SPLASH, 6, 0.15, 0.1));
        standard(
                "lightning", "Lightning", 19, WEAPON, LEGENDARY,
                "b85bcf7f82d34db89a95addf8e53253e2d9554c6fd2f2e39e24362d243a0ccf7",
                "Lightning strikes on enemy death", puff(Particle.ELECTRIC_SPARK, 6, 0.5, 0.2));
        standard(
                "rainbow", "Rainbow", 20, BOOTS, LEGENDARY,
                "72114a80357463fe2f59e397aab9fc66d482a65d524f8870d21c724c18ecf757",
                "Multicoloured potion particles", rainbow(3, 0.3, 1.3f));

        // ------------------------------------------------------------------ Slayer
        slayer(
                "pestilence", "Pestilence", WEAPON, RARE,
                "a8c4811395fbf7f620f05cc3175cef1515aaf775ba04a01045027f0693a90147",
                "Void particles",
                combo(puff(Particle.PORTAL, 10, 0.5, 0.5), dust(40, 0, 60, 1.3f, 4, 0.4)));
        slayer(
                "snake", "Snake", BOOTS, LEGENDARY,
                "2c4a65c689b2d36409100a60c2ab8d3d0a67ce94eea3c1f7ac974fd893568b5d",
                "Green zigzag particle trail", zigzagDust(40, 200, 40));
        slayer(
                "bite", "Bite", BOW, EPIC,
                "43a1ad4fcc42fb63c681328e42d63c83ca193b333af2a426728a25a8cc600692",
                "Green stars and lava particles",
                combo(puff(Particle.HAPPY_VILLAGER, 2, 0.15, 0), puff(Particle.LAVA, 1, 0.1, 0)));
        slayer(
                "darkness_within", "Darkness Within", BOOTS, LEGENDARY, null,
                "Special visual effect",
                combo(
                        puff(Particle.SCULK_SOUL, 1, 0.3, 0.02),
                        dust(10, 0, 20, 1.6f, 4, 0.3)));
        slayer(
                "spirit", "Spirit", WEAPON, RARE,
                "c738b8af8d7ce1a26dc6d40180b3589403e11ef36a66d7c4590037732829542e",
                "Enchanting and aqua particles",
                combo(puff(Particle.ENCHANT, 8, 0.5, 0.5), puff(Particle.SPLASH, 8, 0.4, 0.1)));
        slayer(
                "couture", "Couture", BOOTS, LEGENDARY,
                "734fb3203233efbae82628bd4fca7348cd071e5b7b52407f1d1d2794e31799ff",
                "Flame and critical-hit spiral",
                combo(spiral(Particle.FLAME, 0.5, 1.0), spiral(Particle.CRIT, 0.45, 1.0)));
        slayer(
                "endersnake", "Endersnake", BOOTS, LEGENDARY,
                "c3a9acbb7d3d49b1d54d26111104d0da57d8b4ab37885b4bbd240ac71074cad2",
                "Zigzag witch particles", zigzag(Particle.WITCH));
        slayer(
                "end", "End", BOW, EPIC,
                "3b11fb90db7f57beb435954013b1c7ef776c6bd96cbf3308aa8ebac29591ebbd",
                "Black smoke and witch particles",
                combo(puff(Particle.LARGE_SMOKE, 1, 0.1, 0), puff(Particle.WITCH, 2, 0.15, 0)));
        slayer(
                "enchant", "Enchant", CHESTPLATE, LEGENDARY,
                "59ffacec6ee5a23d9cb24a2fe9dc15b24488f5f71006924560bf12148421ae6d",
                "Enchanting particles move towards player", enchantIn(2.0));
        slayer(
                "lavatears", "Lavatears", BOW, LEGENDARY,
                "8c8ccd5f863d82bb097b926bc5f4cca97b19f46e11b3a3a59d001adb89886773",
                "Special lava-themed effect",
                combo(puff(Particle.FALLING_LAVA, 2, 0.15, 0), puff(Particle.LAVA, 1, 0.1, 0)));
        slayer(
                "fiery_burst", "Fiery Burst", HOE, LEGENDARY,
                "8d620e4e3d3abfed6ad81a58a56bcd085d9e9efc803cabb21fa6c9e3969e2d2e",
                "Special fiery effect",
                combo(puff(Particle.FLAME, 15, 0.5, 0.1), puff(Particle.LAVA, 3, 0.4, 0)));
        slayer(
                "soultwist", "Soultwist", WEAPON, EPIC, null,
                "Witch and smoke particles",
                combo(puff(Particle.WITCH, 8, 0.5, 0), puff(Particle.SMOKE, 6, 0.4, 0.02)));

        // ------------------------------------------------------------------ Event
        event(
                "ice", "Ice", BOW, null,
                "White smoke trail", puff(Particle.CLOUD, 2, 0.1, 0.01));
        event(
                "tidal", "Tidal", BOOTS,
                "69671047c6ade8a9c4d6a581bc26d284a54ae32e85c34ce69d81f92799bf3fbb",
                "Water wave trail", wave(Particle.SPLASH, 14));
        event(
                "jerry", "Jerry", WEAPON,
                "a1353ba65b6521f922b6464d17af39bddd43c984b8a227d99161594eba7a14c4",
                "Villager spawn egg particles", item(Material.VILLAGER_SPAWN_EGG, 6, 0.4));

        // ------------------------------------------------------------------ Fire Sale
        fireSale(
                "grand_searing", "Grand Searing", CHESTPLATE,
                "Flame spiral around legs", spiral(Particle.FLAME, 0.55, 1.0));
        fireSale(
                "grand_freezing", "Grand Freezing", CHESTPLATE,
                "Special freezing effect",
                combo(spiral(Particle.SNOWFLAKE, 0.6, 1.8), puff(Particle.CLOUD, 1, 0.4, 0.01)));
        fireSale(
                "spellbound", "Spellbound", HELMET,
                "Special magical effect",
                combo(orbit(Particle.ENCHANT, 0.4, 2.1, 3, null),
                        above(Particle.WITCH, 2.2, 1)));
        fireSale(
                "primal_fear", "Primal Fear", CHESTPLATE,
                "Firework and witch particle aura",
                combo(puff(Particle.FIREWORK, 1, 0.6, 0.01), puff(Particle.WITCH, 1, 0.6, 0)));
        fireSale(
                "ice_skates", "Ice Skates", BOOTS,
                "White sparkles and ice trail",
                combo(puff(Particle.END_ROD, 1, 0.3, 0.01), puff(Particle.SNOWFLAKE, 2, 0.3, 0.01)));
        fireSale(
                "bark_tunes", "Bark Tunes", WEAPON,
                "Special music effect", combo(note(), sound("entity.wolf.ambient", 1.3f)));
        fireSale(
                "meow_music", "Meow Music", WEAPON,
                "Special music effect", combo(note(), sound("entity.cat.ambient", 1.4f)));
        fireSale(
                "golden_carpet", "Golden Carpet", BOOTS,
                "Flame and golden particle trails",
                combo(puff(Particle.FLAME, 1, 0.2, 0.01), dust(255, 200, 30, 1.2f, 3, 0.3)));
        fireSale(
                "smitten", "Smitten", HELMET,
                "Heart above head", above(Particle.HEART, 2.3, 1));
        fireSale(
                "rainy_day", "Rainy Day", HELMET,
                "Cloud raining over player",
                combo(above(Particle.CLOUD, 2.6, 2), above(Particle.FALLING_WATER, 2.4, 3)));
        fireSale(
                "super_pumpkin", "Super Pumpkin", CHESTPLATE,
                "Pumpkin surrounds player",
                orbitBlock(Particle.BLOCK, 0.9, 1.0, 3, Material.PUMPKIN));
        fireSale(
                "ornamental", "Ornamental", CHESTPLATE,
                "Special cosmetic effect",
                combo(spiral(Particle.END_ROD, 0.6, 1.8), puff(Particle.HAPPY_VILLAGER, 1, 0.5, 0)));
        fireSale(
                "heartsplosion", "Heartsplosion", WEAPON,
                "Heart-themed effect", puff(Particle.HEART, 8, 0.6, 0.05));
        fireSale(
                "blooming", "Blooming", CHESTPLATE,
                "Floating flowers",
                combo(orbitItem(Particle.ITEM, 0.8, 1.2, 3, Material.ALLIUM),
                        orbitItem(Particle.ITEM, 0.8, 0.7, 3, Material.PINK_TULIP)));
        fireSale(
                "flowery_carpet", "Flowery Carpet", BOOTS,
                "Green sparkles and colourful particles",
                combo(puff(Particle.HAPPY_VILLAGER, 2, 0.3, 0), rainbow(2, 0.3, 1f)));
        fireSale(
                "grand_leafing", "Grand Leaf-ing", CHESTPLATE,
                "Flowers and cherry blossom leaves",
                combo(puff(Particle.CHERRY_LEAVES, 2, 0.6, 0),
                        orbitItem(Particle.ITEM, 0.8, 1.1, 3, Material.CORNFLOWER)));
        fireSale(
                "soul_slice", "Soul Slice", AXE,
                "Blue skulls and ominous particles",
                combo(puff(Particle.SOUL_FIRE_FLAME, 6, 0.4, 0.05),
                        puff(Particle.SOUL, 3, 0.4, 0.05)));
        fireSale(
                "turkey", "Turkey", CHESTPLATE,
                "Turkey-tail particles",
                fan(new int[][] {{150, 80, 20}, {200, 60, 20}, {230, 160, 30}, {120, 60, 20}}));
        fireSale(
                "peafowl", "Peafowl", CHESTPLATE,
                "Peacock-tail particles",
                fan(new int[][] {{20, 120, 200}, {20, 180, 120}, {60, 80, 220}, {40, 200, 160}}));
        fireSale(
                "antlers", "Antlers", HELMET,
                "Brown antlers", antlers(120, 80, 40));
        fireSale(
                "crowned", "Crowned", HELMET,
                "Golden crown with gems",
                combo(crown(255, 200, 30), above(Particle.HAPPY_VILLAGER, 2.35, 1)));
        fireSale(
                "fading_rainbow", "Fading Rainbow", AXE,
                "Rainbow particles", rainbow(6, 0.5, 1.4f));
        fireSale(
                "grand_gardening", "Grand Gardening", CHESTPLATE,
                "Two green waves around player",
                combo(spiral(Particle.HAPPY_VILLAGER, 0.7, 1.8), wave(Particle.HAPPY_VILLAGER, 12)));
    }
}
