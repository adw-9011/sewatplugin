package io.github.near.sweats.runes;

import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.storage.YamlFile;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The custom Rune Table: a craftable item placed as an invisible barrier hitbox, an
 * {@link ItemDisplay} (the resource-pack model) and an {@link Interaction} click target.
 * Only players with table access or the rune admin permission can craft and use it.
 */
public final class RuneTable implements Listener {

    private final JavaPlugin plugin;
    private final RuneGui gui;
    private final RuneAccessStore access;
    private final PermissionService permissions;

    private final NamespacedKey itemKey;
    private final NamespacedKey entityKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey recipeKey;
    private final NamespacedKey modelKey;
    private final YamlFile storeFile;
    private final Map<UUID, TableRecord> tables = new LinkedHashMap<>();
    private BukkitTask maintenanceTask;

    public RuneTable(
            final JavaPlugin plugin,
            final RuneGui gui,
            final RuneAccessStore access,
            final PermissionService permissions) {
        this.plugin = plugin;
        this.gui = gui;
        this.access = access;
        this.permissions = permissions;
        this.itemKey = new NamespacedKey(plugin, "rune_table_item");
        this.entityKey = new NamespacedKey(plugin, "rune_table");
        this.displayKey = new NamespacedKey(plugin, "rune_table_display");
        this.ownerKey = new NamespacedKey(plugin, "rune_table_owner");
        this.recipeKey = new NamespacedKey(plugin, "rune_table_recipe");
        this.modelKey = NamespacedKey.fromString("runes:rune_table");
        this.storeFile = new YamlFile(plugin.getDataFolder().toPath().resolve("runetables.yml"));
        loadTables();
    }

    /** Starts the periodic orphan cleanup; loaded tables themselves are left untouched. */
    public void startMaintenance() {
        if (maintenanceTask != null) {
            return;
        }
        loadTables();
        cleanupOrphans();
        maintenanceTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::cleanupOrphans, 20L * 60L, 20L * 300L);
    }

    /**
     * Flushes the registry and stops cleanup. Deliberately leaves placed tables and their
     * barrier blocks alive so a normal reload or restart does not destroy builds.
     */
    public void close() {
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
        saveTables();
    }

    // ------------------------------------------------------------------ item

    public ItemStack item() {
        final ItemStack item = new ItemStack(Material.ENCHANTING_TABLE);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(RuneItems.line("Rune Table", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
                RuneItems.line("Combine runes and apply them", NamedTextColor.GRAY),
                RuneItems.line("to your gear.", NamedTextColor.GRAY),
                Component.empty(),
                RuneItems.line("Place it down and right-click it.", NamedTextColor.DARK_GRAY)));
        meta.setItemModel(modelKey);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isTableItem(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta()
                .getPersistentDataContainer()
                .has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ ownership registry

    private record TableRecord(
            UUID owner, UUID display, String world, int x, int y, int z) {}

    private void loadTables() {
        tables.clear();
        final YamlConfiguration config = storeFile.load(plugin.getLogger()::warning);
        if (config == null) {
            return;
        }
        final ConfigurationSection section = config.getConfigurationSection("tables");
        if (section == null) {
            return;
        }
        for (final String rawId : section.getKeys(false)) {
            final UUID interactionId = parseUuid(rawId);
            final String world = section.getString(rawId + ".world");
            if (interactionId == null || world == null || world.isBlank()) {
                plugin.getLogger().warning("Skipped invalid Rune Table entry '" + rawId + "'.");
                continue;
            }
            tables.put(interactionId, new TableRecord(
                    parseUuid(section.getString(rawId + ".owner", "")),
                    parseUuid(section.getString(rawId + ".display", "")),
                    world,
                    section.getInt(rawId + ".x"),
                    section.getInt(rawId + ".y"),
                    section.getInt(rawId + ".z")));
        }
    }

    private void saveTables() {
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Placed Rune Tables. Ownership is also stored on the interaction entity, so",
                "deleting this file only affects offline cleanup, not who may remove a table."));
        for (final Map.Entry<UUID, TableRecord> entry : tables.entrySet()) {
            final String path = "tables." + entry.getKey();
            final TableRecord record = entry.getValue();
            if (record.owner() != null) {
                config.set(path + ".owner", record.owner().toString());
            }
            if (record.display() != null) {
                config.set(path + ".display", record.display().toString());
            }
            config.set(path + ".world", record.world());
            config.set(path + ".x", record.x());
            config.set(path + ".y", record.y());
            config.set(path + ".z", record.z());
        }
        storeFile.save(config, plugin.getLogger()::warning);
    }

    /**
     * Drops registry entries whose entity is gone and clears the barrier block they left
     * behind. Chunks that are not loaded are skipped: their entities may still exist on disk.
     */
    private void cleanupOrphans() {
        boolean changed = false;
        final Iterator<Map.Entry<UUID, TableRecord>> iterator = tables.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, TableRecord> entry = iterator.next();
            final TableRecord record = entry.getValue();
            final World world = Bukkit.getWorld(record.world());
            if (world == null) {
                iterator.remove();
                changed = true;
                continue;
            }
            if (!world.isChunkLoaded(record.x() >> 4, record.z() >> 4)) {
                continue;
            }
            final Entity interaction = world.getEntity(entry.getKey());
            if (interaction != null && interaction.isValid()) {
                if (record.owner() != null
                        && !interaction.getPersistentDataContainer()
                                .has(ownerKey, PersistentDataType.STRING)) {
                    interaction.getPersistentDataContainer().set(
                            ownerKey, PersistentDataType.STRING, record.owner().toString());
                }
                continue;
            }

            final Block block = world.getBlockAt(record.x(), record.y(), record.z());
            if (block.getType() == Material.BARRIER && findAt(block) == null) {
                block.setType(Material.AIR, false);
            }
            iterator.remove();
            changed = true;
        }
        if (changed) {
            saveTables();
        }
    }

    private static UUID parseUuid(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }

    private UUID ownerOf(final Interaction interaction) {
        final UUID stored = parseUuid(interaction.getPersistentDataContainer()
                .get(ownerKey, PersistentDataType.STRING));
        if (stored != null) {
            return stored;
        }
        final TableRecord record = tables.get(interaction.getUniqueId());
        return record == null ? null : record.owner();
    }

    /**
     * The owner may remove their own table; administrators may remove any table. A legacy
     * table created before ownership existed has no owner and is therefore admin-only.
     */
    boolean canRemove(final Player player, final Interaction interaction) {
        if (permissions.has(player, PermissionNodes.RUNES_ADMIN)) {
            return true;
        }
        final UUID owner = ownerOf(interaction);
        return owner != null && owner.equals(player.getUniqueId());
    }

    private static void denyRemove(final Player player) {
        player.sendMessage(Component.text(
                "Only the owner or an admin can remove this Rune Table.",
                NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ access

    public boolean canUse(final Player player) {
        return permissions.has(player, PermissionNodes.RUNES_ADMIN)
                || access.has(player.getUniqueId());
    }

    private static void deny(final Player player) {
        player.sendMessage(Component.text(
                "You don't have access to the Rune Table.", NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ recipe

    public void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        final List<String> shape = plugin.getConfig().getStringList("runes.table.recipe.shape");
        final ConfigurationSection ingredients =
                plugin.getConfig().getConfigurationSection("runes.table.recipe.ingredients");
        if (shape.size() != 3 || ingredients == null) {
            plugin.getLogger().warning(
                    "runes.table.recipe is missing/invalid in config.yml - Rune Table recipe not registered.");
            return;
        }
        try {
            final ShapedRecipe recipe = new ShapedRecipe(recipeKey, item());
            recipe.shape(shape.toArray(new String[0]));
            for (final String key : ingredients.getKeys(false)) {
                final Material material = Material.matchMaterial(ingredients.getString(key, ""));
                if (material == null) {
                    throw new IllegalArgumentException("Unknown material for '" + key + "'");
                }
                recipe.setIngredient(key.charAt(0), material);
            }
            Bukkit.addRecipe(recipe);
        } catch (final IllegalArgumentException exception) {
            plugin.getLogger().warning("Rune Table recipe invalid: " + exception.getMessage());
        }
    }

    private boolean isOurRecipe(final Object recipe) {
        return recipe instanceof final Keyed keyed && keyed.getKey().equals(recipeKey);
    }

    @EventHandler
    public void onPrepare(final PrepareItemCraftEvent event) {
        if (!isOurRecipe(event.getRecipe())) {
            return;
        }
        if (!(event.getView().getPlayer() instanceof final Player player) || !canUse(player)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onCraft(final CraftItemEvent event) {
        if (!isOurRecipe(event.getRecipe())) {
            return;
        }
        if (!(event.getWhoClicked() instanceof final Player player) || !canUse(player)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof final Player denied) {
                deny(denied);
            }
        }
    }

    // ------------------------------------------------------------------ placing

    @EventHandler(ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (!isTableItem(event.getItemInHand())) {
            return;
        }
        final Player player = event.getPlayer();
        if (!canUse(player)) {
            event.setCancelled(true);
            deny(player);
            return;
        }
        final Block block = event.getBlockPlaced();
        block.setType(Material.BARRIER, false);
        spawn(block, player.getLocation().getYaw(), player.getUniqueId());
    }

    private void spawn(final Block block, final float playerYaw, final UUID owner) {
        final float yaw = Math.round((playerYaw + 180f) / 90f) * 90f; // front faces the player
        final Location center = block.getLocation().add(0.5, 0.5, 0.5);
        final ItemDisplay display = block.getWorld().spawn(center, ItemDisplay.class, created -> {
            created.setItemStack(item());
            created.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            created.setRotation(yaw, 0f);
            created.setPersistent(true);
            created.getPersistentDataContainer().set(entityKey, PersistentDataType.BYTE, (byte) 1);
        });
        final Interaction interaction = block.getWorld().spawn(
                block.getLocation().add(0.5, 0, 0.5), Interaction.class, created -> {
                    created.setInteractionWidth(1.0f);
                    created.setInteractionHeight(1.0f);
                    created.setResponsive(true);
                    created.setPersistent(true);
                    created.getPersistentDataContainer()
                            .set(entityKey, PersistentDataType.BYTE, (byte) 1);
                    created.getPersistentDataContainer().set(
                            ownerKey, PersistentDataType.STRING, owner.toString());
                    created.getPersistentDataContainer().set(
                            displayKey,
                            PersistentDataType.STRING,
                            display.getUniqueId().toString());
                });
        tables.put(interaction.getUniqueId(), new TableRecord(
                owner,
                display.getUniqueId(),
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()));
        saveTables();
    }

    // ------------------------------------------------------------------ using / breaking

    private boolean isTable(final Entity entity) {
        return entity instanceof Interaction
                && entity.isValid()
                && entity.getPersistentDataContainer().has(entityKey, PersistentDataType.BYTE);
    }

    private Interaction findAt(final Block block) {
        final Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (final Entity entity : block.getWorld().getNearbyEntities(center, 0.6, 0.6, 0.6)) {
            if (isTable(entity)) {
                return (Interaction) entity;
            }
        }
        return null;
    }

    private void remove(final Interaction interaction, final Player breaker) {
        final Location base = interaction.getLocation();
        final Block block = base.getBlock();
        final String displayId =
                interaction.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
        if (displayId != null) {
            try {
                final Entity display = Bukkit.getEntity(UUID.fromString(displayId));
                if (display != null) {
                    display.remove();
                }
            } catch (final IllegalArgumentException ignored) {
                // The display UUID was corrupted; the interaction still gets cleaned up.
            }
        }
        interaction.remove();
        tables.remove(interaction.getUniqueId());
        saveTables();
        if (block.getType() == Material.BARRIER) {
            block.setType(Material.AIR, false);
        }
        if (breaker == null || breaker.getGameMode() != GameMode.CREATIVE) {
            block.getWorld().dropItemNaturally(base.clone().add(0, 0.5, 0), item());
        }
    }

    @EventHandler
    public void onUse(final PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof final Interaction interaction)
                || !isTable(interaction)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player player = event.getPlayer();
        // Backup way to pick the table up: sneak + right-click with an empty hand.
        if (player.isSneaking() && player.getInventory().getItemInMainHand().getType().isAir()) {
            if (!canRemove(player, interaction)) {
                denyRemove(player);
                return;
            }
            remove(interaction, player);
            return;
        }
        if (!canUse(player)) {
            deny(player);
            return;
        }
        gui.openTable(player);
    }

    @EventHandler
    public void onAttack(final PrePlayerAttackEntityEvent event) {
        final Entity target = event.getAttacked();
        if (!isTable(target)) {
            return;
        }
        event.setCancelled(true);
        if (!canRemove(event.getPlayer(), (Interaction) target)) {
            denyRemove(event.getPlayer());
            return;
        }
        remove((Interaction) target, event.getPlayer());
    }

    @EventHandler
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (!isTable(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        final Interaction interaction = (Interaction) event.getEntity();
        if (!(event.getDamager() instanceof final Player player)) {
            return;
        }
        if (!canRemove(player, interaction)) {
            denyRemove(player);
            return;
        }
        remove(interaction, player);
    }

    @EventHandler
    public void onBreak(final BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.BARRIER) {
            return;
        }
        final Interaction interaction = findAt(event.getBlock());
        if (interaction == null) {
            return;
        }
        event.setCancelled(true);
        if (!canRemove(event.getPlayer(), interaction)) {
            denyRemove(event.getPlayer());
            return;
        }
        remove(interaction, event.getPlayer());
    }
}
