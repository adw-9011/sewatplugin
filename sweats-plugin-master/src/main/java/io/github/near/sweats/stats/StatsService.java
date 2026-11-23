package io.github.near.sweats.stats;

import io.github.near.sweats.SweatsPlugin;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Gathers the numbers behind {@code /stats} and keeps the one counter vanilla does not have.
 *
 * <p>Vanilla statistics (playtime, distance, mined blocks, item uses, kills, deaths) are read from
 * the player's own statistic map. Blocks placed are not a vanilla statistic at all, so they are
 * counted from {@link BlockPlaceEvent}s and stored in {@code stats.yml}. That counter starts at
 * zero for a player the first time this version of the plugin sees them place a block.
 */
public final class StatsService implements Listener {

    /** Material.values() copies its array on every call, so it is cached once. */
    private static final Material[] MATERIALS = Material.values();

    private static final Material[] BLOCKS = filter(MATERIALS, Material::isBlock);
    private static final Material[] ITEMS = filter(MATERIALS, Material::isItem);

    /** Every movement statistic, in centimetres. Vertical fall and climb are left out on purpose. */
    private static final List<Statistic> MOVEMENT = List.of(
            Statistic.WALK_ONE_CM,
            Statistic.SPRINT_ONE_CM,
            Statistic.CROUCH_ONE_CM,
            Statistic.SWIM_ONE_CM,
            Statistic.WALK_ON_WATER_ONE_CM,
            Statistic.WALK_UNDER_WATER_ONE_CM,
            Statistic.FLY_ONE_CM,
            Statistic.AVIATE_ONE_CM,
            Statistic.BOAT_ONE_CM,
            Statistic.MINECART_ONE_CM,
            Statistic.HORSE_ONE_CM,
            Statistic.PIG_ONE_CM,
            Statistic.STRIDER_ONE_CM,
            Statistic.HAPPY_GHAST_ONE_CM,
            Statistic.NAUTILUS_ONE_CM);

    private final SweatsPlugin plugin;
    private final StatsStore store;

    public StatsService(final SweatsPlugin plugin) {
        this.plugin = plugin;
        this.store = new StatsStore(plugin.getDataFolder().toPath().resolve("stats.yml"));
        this.store.load(plugin.getLogger()::warning);

        // Placing blocks only touches memory; the file is written at most once a minute.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::saveIfDirty, 1_200L, 1_200L);
    }

    /** Reads the lifetime counters shared with the scoreboard. Kills exclude mob kills. */
    public PlayerProfile profile(final Player player) {
        return new PlayerProfile(
                statistic(player, Statistic.PLAY_ONE_MINUTE),
                statistic(player, Statistic.PLAYER_KILLS),
                statistic(player, Statistic.DEATHS));
    }

    /**
     * Lifetime playtime in ticks, for an online or an offline player.
     *
     * <p>Online players are read from memory; offline players have their statistics loaded from
     * disk by the server. A player without statistics, or a build without the statistic, counts
     * as zero so a leaderboard never fails on one bad row.
     */
    public long playtimeTicks(final OfflinePlayer player) {
        return statistic(player, Statistic.PLAY_ONE_MINUTE);
    }

    /** Lifetime player kills, for the kills leaderboard. */
    public long playerKills(final OfflinePlayer player) {
        return statistic(player, Statistic.PLAYER_KILLS);
    }

    /** Lifetime deaths, for the deaths leaderboard. */
    public long deaths(final OfflinePlayer player) {
        return statistic(player, Statistic.DEATHS);
    }

    /** Reads one vanilla statistic from an online or offline player, defaulting to zero. */
    private static long statistic(final OfflinePlayer player, final Statistic stat) {
        if (player == null) {
            return 0L;
        }
        final Player online = player.isOnline() ? player.getPlayer() : null;
        if (online != null) {
            return statistic(online, stat);
        }
        try {
            return Math.max(0L, player.getStatistic(stat));
        } catch (final RuntimeException exception) {
            return 0L;
        }
    }

    /** Reads every statistic for one player. Offline players only get the stored counters. */
    public StatsSnapshot snapshot(final OfflinePlayer target) {
        final Player online = target.isOnline() ? target.getPlayer() : null;

        long playtimeTicks = 0L;
        long distanceCm = 0L;
        long blocksMined = 0L;
        long itemsUsed = 0L;
        int deaths = 0;
        int playerKills = 0;
        int mobKills = 0;
        int ping = 0;
        String world = null;
        Integer x = null;
        Integer y = null;
        Integer z = null;

        if (online != null) {
            playtimeTicks = statistic(online, Statistic.PLAY_ONE_MINUTE);
            distanceCm = movement(online);
            blocksMined = sumOverMaterials(online, Statistic.MINE_BLOCK, BLOCKS);
            itemsUsed = sumOverMaterials(online, Statistic.USE_ITEM, ITEMS);
            deaths = statistic(online, Statistic.DEATHS);
            playerKills = statistic(online, Statistic.PLAYER_KILLS);
            mobKills = statistic(online, Statistic.MOB_KILLS);
            ping = online.getPing();

            final Location location = online.getLocation();
            if (location.getWorld() != null) {
                world = location.getWorld().getName();
                x = location.getBlockX();
                y = location.getBlockY();
                z = location.getBlockZ();
            }
        }

        return new StatsSnapshot(
                target.getUniqueId(),
                nameOf(target),
                online != null,
                online != null,
                target.getFirstPlayed(),
                target.getLastSeen(),
                playtimeTicks,
                distanceCm,
                blocksMined,
                itemsUsed,
                store.blocksPlaced(target.getUniqueId()),
                deaths,
                playerKills,
                mobKills,
                world,
                x,
                y,
                z,
                ping);
    }

    /** Counts the block someone just placed; vanilla has no statistic for this. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        final Player player = event.getPlayer();
        store.addBlockPlaced(player.getUniqueId(), player.getName());
    }

    public List<String> knownNames() {
        return store.knownNames();
    }

    public void saveIfDirty() {
        if (store.dirty()) {
            store.save(plugin.getLogger()::warning);
        }
    }

    public void save() {
        store.save(plugin.getLogger()::warning);
    }

    private static String nameOf(final OfflinePlayer target) {
        final String name = target.getName();
        return name == null || name.isBlank() ? target.getUniqueId().toString() : name;
    }

    private static int statistic(final Player player, final Statistic statistic) {
        try {
            return Math.max(0, player.getStatistic(statistic));
        } catch (final RuntimeException exception) {
            // A statistic that this server build does not expose counts as zero.
            return 0;
        }
    }

    private static long movement(final Player player) {
        long centimetres = 0L;
        for (final Statistic statistic : MOVEMENT) {
            centimetres += statistic(player, statistic);
        }
        return centimetres;
    }

    private static long sumOverMaterials(
            final Player player, final Statistic statistic, final Material[] materials) {
        long total = 0L;
        for (final Material material : materials) {
            try {
                total += Math.max(0, player.getStatistic(statistic, material));
            } catch (final IllegalArgumentException exception) {
                // Not every material is registered for every statistic.
            }
        }
        return total;
    }

    private static Material[] filter(final Material[] materials, final Predicate<Material> predicate) {
        return Arrays.stream(materials).filter(predicate).toArray(Material[]::new);
    }
}
