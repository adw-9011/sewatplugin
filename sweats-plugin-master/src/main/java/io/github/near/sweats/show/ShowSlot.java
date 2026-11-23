package io.github.near.sweats.show;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Every slot {@code /show} knows about, plus the aliases players can type for it. */
public enum ShowSlot {

    MAIN_HAND("main hand", EquipmentSlot.HAND, "hand", "mainhand", "main_hand", "item", "held"),
    OFF_HAND("off hand", EquipmentSlot.OFF_HAND, "offhand", "off_hand", "off", "shield"),
    HELMET("helmet", EquipmentSlot.HEAD, "head", "hat", "cap"),
    CHESTPLATE("chestplate", EquipmentSlot.CHEST, "chest", "body", "shirt"),
    LEGGINGS("leggings", EquipmentSlot.LEGS, "legs", "pants", "trousers"),
    BOOTS("boots", EquipmentSlot.FEET, "feet", "shoe", "shoes");

    private final String label;
    private final EquipmentSlot equipmentSlot;
    private final List<String> aliases;

    ShowSlot(final String label, final EquipmentSlot equipmentSlot, final String... aliases) {
        this.label = label;
        this.equipmentSlot = equipmentSlot;
        this.aliases = List.of(aliases);
    }

    /** Human readable slot name used in messages, e.g. {@code "off hand"}. */
    public String label() {
        return label;
    }

    public EquipmentSlot equipmentSlot() {
        return equipmentSlot;
    }

    public List<String> aliases() {
        return aliases;
    }

    /** The item currently in this slot. Never {@code null}, but may be empty. */
    public ItemStack itemIn(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        return switch (equipmentSlot) {
            case HAND -> inventory.getItemInMainHand();
            case OFF_HAND -> inventory.getItemInOffHand();
            case HEAD -> inventory.getHelmet();
            case CHEST -> inventory.getChestplate();
            case LEGS -> inventory.getLeggings();
            case FEET -> inventory.getBoots();
            default -> ItemStack.empty();
        };
    }

    /** Resolves a user supplied word such as {@code "chest"} or {@code "mainhand"}. */
    public static Optional<ShowSlot> parse(final String input) {
        final String needle = input.toLowerCase(Locale.ROOT).trim();
        return Arrays.stream(values())
                .filter(slot -> slot.name().toLowerCase(Locale.ROOT).equals(needle)
                        || slot.aliases.contains(needle))
                .findFirst();
    }
}
