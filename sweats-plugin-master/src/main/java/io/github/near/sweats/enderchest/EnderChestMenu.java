package io.github.near.sweats.enderchest;

import java.util.UUID;
import io.github.near.sweats.menu.ProtectedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * A 54-slot view over the vanilla ender chest (first 27 slots) plus up to 27 persisted extra slots.
 * The holder is how the service recognises its own inventory. The visible capacity is controlled
 * by the player's rank; extra slots beyond the visible capacity are preserved but inaccessible.
 */
public final class EnderChestMenu implements ProtectedMenu {

    public static final int MAX_TOTAL_SLOTS = EnderChestStore.MAX_TOTAL_SLOTS; // 54
    public static final int VANILLA_SLOTS = EnderChestStore.VANILLA_SLOTS; // 27
    public static final int MAX_EXTRA_SLOTS = EnderChestStore.MAX_EXTRA_SLOTS; // 27

    private final UUID owner;
    private final Inventory inventory;
    private int visibleCapacity = MAX_TOTAL_SLOTS; // Default to full access

    public EnderChestMenu(final UUID owner, final ItemStack[] contents) {
        this.owner = owner;
        this.inventory = Bukkit.createInventory(
                this, MAX_TOTAL_SLOTS, Component.text("Ender Chest", NamedTextColor.DARK_GRAY));
        for (int slot = 0; slot < MAX_TOTAL_SLOTS && slot < contents.length; slot++) {
            final ItemStack item = contents[slot];
            if (item != null && !item.isEmpty()) {
                inventory.setItem(slot, item.clone());
            }
        }
    }

    /** Sets the number of slots the player can interact with (27, 36, or 54). */
    public void setVisibleCapacity(final int capacity) {
        this.visibleCapacity = Math.max(VANILLA_SLOTS, Math.min(MAX_TOTAL_SLOTS, capacity));
    }

    public int getVisibleCapacity() {
        return visibleCapacity;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID owner() {
        return owner;
    }

    @Override
    public UUID ownerId() {
        return owner;
    }

    @Override
    public boolean mutable() {
        return true;
    }

    /** Copies slots 0-26. */
    public ItemStack[] vanillaContents() {
        final ItemStack[] items = new ItemStack[VANILLA_SLOTS];
        for (int slot = 0; slot < VANILLA_SLOTS; slot++) {
            final ItemStack item = inventory.getItem(slot);
            items[slot] = item == null || item.isEmpty() ? null : item.clone();
        }
        return items;
    }

    /** Copies all extra slots (27-53) into a dense array. Callers should only persist up to the visible capacity. */
    public ItemStack[] extraContents() {
        final ItemStack[] items = new ItemStack[MAX_EXTRA_SLOTS];
        for (int slot = 0; slot < MAX_EXTRA_SLOTS; slot++) {
            final ItemStack item = inventory.getItem(VANILLA_SLOTS + slot);
            items[slot] = item == null || item.isEmpty() ? null : item.clone();
        }
        return items;
    }

    /** Copies extra slots up to the visible capacity. */
    public ItemStack[] extraContentsUpToCapacity() {
        final int extraSlots = Math.max(0, visibleCapacity - VANILLA_SLOTS);
        final ItemStack[] items = new ItemStack[extraSlots];
        for (int slot = 0; slot < extraSlots; slot++) {
            final ItemStack item = inventory.getItem(VANILLA_SLOTS + slot);
            items[slot] = item == null || item.isEmpty() ? null : item.clone();
        }
        return items;
    }

    public void open(final Player player) {
        player.openInventory(inventory);
    }
}
