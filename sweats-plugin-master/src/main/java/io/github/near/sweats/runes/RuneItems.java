package io.github.near.sweats.runes;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.github.near.sweats.items.ItemIdentity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

/** Creates and reads rune items, applied runes and the Admin Sword. */
public final class RuneItems {

    /** A rune plus tier, either as a rune item or applied to equipment. */
    public record Held(Rune rune, int tier) {}

    public static final int MAX_TIER = 3;

    private final JavaPlugin plugin;
    private final NamespacedKey runeKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey appliedKey;
    private final NamespacedKey appliedTierKey;
    private final NamespacedKey adminKey;
    private final NamespacedKey adminOwnerKey;

    public RuneItems(final JavaPlugin plugin) {
        this.plugin = plugin;
        this.runeKey = new NamespacedKey(plugin, "rune_item");
        this.tierKey = new NamespacedKey(plugin, "rune_item_tier");
        this.appliedKey = new NamespacedKey(plugin, "applied_rune");
        this.appliedTierKey = new NamespacedKey(plugin, "applied_tier");
        this.adminKey = new NamespacedKey(plugin, "admin_sword");
        this.adminOwnerKey = new NamespacedKey(plugin, "admin_sword_owner");
    }

    public static boolean empty(final ItemStack item) {
        return item == null || item.getType().isAir();
    }

    /** Clamps hand-edited or corrupted tier data to the only tiers the game understands. */
    public static int clampTier(final int tier) {
        return Math.max(1, Math.min(MAX_TIER, tier));
    }

    public static String roman(final int tier) {
        return switch (clampTier(tier)) {
            case 1 -> "I";
            case 2 -> "II";
            default -> "III";
        };
    }

    public static Component line(final String text, final TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private String hash(final Rune rune) {
        final String override = plugin.getConfig().getString("runes.hashes." + rune.id());
        return override != null && !override.isBlank() ? override.trim() : rune.hash();
    }

    // ------------------------------------------------------------------ rune items

    public ItemStack runeItem(final Rune rune, final int requestedTier) {
        final int tier = clampTier(requestedTier);
        ItemStack item;
        final String hash = hash(rune);
        if (hash != null) {
            item = new ItemStack(Material.PLAYER_HEAD);
            final SkullMeta skull = (SkullMeta) item.getItemMeta();
            try {
                final PlayerProfile profile = Bukkit.createPlayerProfile(
                        UUID.nameUUIDFromBytes(
                                ("rune:" + rune.id()).getBytes(StandardCharsets.UTF_8)),
                        "rune");
                final PlayerTextures textures = profile.getTextures();
                textures.setSkin(URI.create(
                                "https://textures.minecraft.net/texture/" + hash)
                        .toURL());
                profile.setTextures(textures);
                skull.setOwnerProfile(profile);
            } catch (final MalformedURLException | IllegalArgumentException exception) {
                plugin.getLogger().warning("Bad texture hash for rune " + rune.id());
            }
            item.setItemMeta(skull);
        } else {
            item = new ItemStack(rune.group().fallback);
        }

        final ItemMeta meta = item.getItemMeta();
        meta.displayName(line(rune.name() + " Rune " + roman(tier), rune.rarity().color)
                .decoration(TextDecoration.BOLD, true));

        final List<Component> lore = new ArrayList<>();
        lore.add(line("Tier " + roman(tier), NamedTextColor.GRAY));
        lore.add(line(rune.group().display + " Rune", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(line("Equipment: ", NamedTextColor.GRAY)
                .append(line(rune.equip().display, NamedTextColor.YELLOW)));
        lore.add(line("Effect: ", NamedTextColor.GRAY)
                .append(line(rune.desc(), NamedTextColor.AQUA)));
        if (rune.level() > 0) {
            lore.add(line("Level: ", NamedTextColor.GRAY)
                    .append(line(String.valueOf(rune.level()), NamedTextColor.GREEN)));
        }
        lore.add(Component.empty());
        if (tier < MAX_TIER) {
            lore.add(line("Combine 2 identical runes in the", NamedTextColor.DARK_GRAY));
            lore.add(line("Runic Table to upgrade the tier.", NamedTextColor.DARK_GRAY));
        } else {
            lore.add(line("MAX TIER", NamedTextColor.GOLD));
        }
        lore.add(line(rune.rarity().name() + " RUNE", rune.rarity().color)
                .decoration(TextDecoration.BOLD, true));
        meta.lore(lore);
        meta.setMaxStackSize(1);

        final PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(runeKey, PersistentDataType.STRING, rune.id());
        data.set(tierKey, PersistentDataType.INTEGER, tier);
        item.setItemMeta(meta);
        ItemIdentity.assign(plugin, item);
        return item;
    }

    /** Reads a rune item. */
    public Held asRune(final ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) {
            return null;
        }
        final PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        final Rune rune = RuneCatalog.get(data.get(runeKey, PersistentDataType.STRING));
        if (rune == null) {
            return null;
        }
        final Integer tier = data.get(tierKey, PersistentDataType.INTEGER);
        return new Held(rune, tier == null ? 1 : clampTier(tier));
    }

    // ------------------------------------------------------------------ equipment

    /** Reads the rune applied to a piece of equipment. */
    public Held applied(final ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) {
            return null;
        }
        final PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        final Rune rune = RuneCatalog.get(data.get(appliedKey, PersistentDataType.STRING));
        if (rune == null) {
            return null;
        }
        final Integer tier = data.get(appliedTierKey, PersistentDataType.INTEGER);
        return new Held(rune, tier == null ? 1 : clampTier(tier));
    }

    public ItemStack applyRune(final ItemStack base, final Rune rune, final int tier) {
        final ItemStack out = base.clone();
        out.setAmount(1);
        final int safeTier = clampTier(tier);
        final ItemMeta meta = out.getItemMeta();
        final PersistentDataContainer data = meta.getPersistentDataContainer();
        final List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        if (data.has(appliedKey, PersistentDataType.STRING) && !lore.isEmpty()) {
            lore.remove(lore.size() - 1);
        }
        lore.add(line("\u25C6 " + rune.name() + " Rune " + roman(safeTier), rune.rarity().color));
        data.set(appliedKey, PersistentDataType.STRING, rune.id());
        data.set(appliedTierKey, PersistentDataType.INTEGER, safeTier);
        meta.lore(lore);
        out.setItemMeta(meta);
        ItemIdentity.ensure(plugin, out);
        return out;
    }

    public ItemStack removeRune(final ItemStack base) {
        final ItemStack out = base.clone();
        final ItemMeta meta = out.getItemMeta();
        final PersistentDataContainer data = meta.getPersistentDataContainer();
        if (!data.has(appliedKey, PersistentDataType.STRING)) {
            return out;
        }
        final List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        if (!lore.isEmpty()) {
            lore.remove(lore.size() - 1);
        }
        data.remove(appliedKey);
        data.remove(appliedTierKey);
        meta.lore(lore.isEmpty() ? null : lore);
        out.setItemMeta(meta);
        return out;
    }

    // ------------------------------------------------------------------ admin sword

    /** Sword that one-taps bosses and shows every weapon rune effect. Bound to its owner. */
    public ItemStack adminSword(final UUID owner) {
        final ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(line("Admin Sword", NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, true));
        final List<Component> lore = new ArrayList<>();
        lore.add(line("One-taps every boss.", NamedTextColor.GRAY));
        lore.add(line("Has every weapon rune (Tier III).", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(line("ADMIN", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        meta.lore(lore);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(adminKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer()
                .set(adminOwnerKey, PersistentDataType.STRING, owner.toString());
        item.setItemMeta(meta);
        ItemIdentity.assign(plugin, item);
        return item;
    }

    public boolean isAdminSword(final ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta()
                .getPersistentDataContainer()
                .has(adminKey, PersistentDataType.BYTE);
    }

    /** True only for the sword's owner; an unbound legacy sword is not usable. */
    public boolean isAdminSword(final ItemStack item, final UUID owner) {
        if (owner == null || !isAdminSword(item)) {
            return false;
        }
        final String stored = item.getItemMeta().getPersistentDataContainer()
                .get(adminOwnerKey, PersistentDataType.STRING);
        return owner.toString().equals(stored);
    }
}
