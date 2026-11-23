package io.github.near.sweats.leaderboard;

import io.github.near.sweats.SweatsPlugin;
import io.github.near.sweats.afk.AfkService;
import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.players.RealPlayerRegistry;
import io.github.near.sweats.stats.StatsService;
import io.github.near.sweats.vote.VoteStore;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps the op-placed leaderboard boards alive.
 *
 * <p>Every category is ranked from confirmed real players only, so cached NPC shop profiles can
 * never appear. Vanilla statistics supply playtime, deaths and kills; AFK time and votes come from
 * Sweats' own stores. Offline players stay on the boards once they have joined at least once, while
 * each viewer's own line is read live.
 */
public final class LeaderboardService implements Listener, LeaderboardBoards {

    private final SweatsPlugin plugin;
    private final StatsService stats;
    private final NickService nicks;
    private final RealPlayerRegistry realPlayers;
    private final AfkService afk;
    private final VoteStore votes;
    private final Predicate<UUID> visible;
    private final LeaderboardStore store;
    private final Map<UUID, LeaderboardDisplay> displays = new LinkedHashMap<>();
    private final Map<LeaderboardCategory, Map<UUID, LeaderboardEntry>> entries =
            new EnumMap<>(LeaderboardCategory.class);
    private final Map<LeaderboardCategory, List<LeaderboardEntry>> rankings =
            new EnumMap<>(LeaderboardCategory.class);

    private LeaderboardSettings settings;
    private BukkitTask refreshTask;
    private BukkitTask rankingTask;

    public LeaderboardService(
            final SweatsPlugin plugin,
            final StatsService stats,
            final NickService nicks,
            final RealPlayerRegistry realPlayers,
            final AfkService afk,
            final VoteStore votes,
            final Predicate<UUID> visible) {
        this.plugin = plugin;
        this.stats = stats;
        this.nicks = nicks;
        this.realPlayers = realPlayers;
        this.afk = afk;
        this.votes = votes;
        this.visible = visible;
        this.store = new LeaderboardStore(
                plugin.getDataFolder().toPath().resolve("leaderboards.yml"),
                plugin.getLogger()::warning);
        for (final LeaderboardCategory category : LeaderboardCategory.values()) {
            entries.put(category, new LinkedHashMap<>());
            rankings.put(category, List.of());
        }
        this.store.load();
        this.settings = LeaderboardSettings.from(plugin.getConfig());

        rebuildDisplays();
        if (settings.enabled()) {
            refreshRanking();
        }
        startTasks();
    }

    public LeaderboardSettings settings() {
        return settings;
    }

    @Override
    public boolean enabled() {
        return settings.enabled();
    }

    @Override
    public List<LeaderboardStore.StoredDisplay> displays() {
        return store.all();
    }

    /** Re-reads config.yml and recreates the display entities around the saved positions. */
    public void reloadSettings() {
        clearState();
        this.settings = LeaderboardSettings.from(plugin.getConfig());
        rebuildDisplays();
        if (settings.enabled()) {
            refreshRanking();
        }
        startTasks();
    }

    /** Removes every private viewer entity. Called on shutdown and before a reload. */
    public void clearState() {
        stopTasks();
        for (final LeaderboardDisplay display : displays.values()) {
            display.removeAll();
        }
        displays.clear();
        clearRanking();
    }

    /** Places a new playtime board and returns the saved position. */
    @Override
    public LeaderboardStore.StoredDisplay place(final Location location) {
        return place(location, LeaderboardCategory.PLAYTIME);
    }

    /** Places a new board of the chosen category and returns the saved position. */
    @Override
    public LeaderboardStore.StoredDisplay place(
            final Location location, final LeaderboardCategory category) {
        final LeaderboardStore.StoredDisplay stored = store.add(location, category);
        final Location live = stored.location();
        if (live != null) {
            displays.put(stored.id(), new LeaderboardDisplay(this, stored, live));
        }
        refresh();
        return stored;
    }

    /** Removes the nearest board within {@code radius} blocks, if there is one. */
    @Override
    public boolean removeNearest(final Location location, final double radius) {
        final Optional<LeaderboardStore.StoredDisplay> nearest = store.nearest(location, radius);
        if (nearest.isEmpty()) {
            return false;
        }

        final LeaderboardStore.StoredDisplay stored = nearest.get();
        store.remove(stored.id());
        final LeaderboardDisplay display = displays.remove(stored.id());
        if (display != null) {
            display.removeAll();
        }
        return true;
    }

    /**
     * Rebuilds every category from confirmed real players. This is the expensive path, run at
     * startup, on reload and after a vote. The periodic task only updates online players.
     */
    public void refreshRanking() {
        if (!settings.enabled()) {
            clearRanking();
            return;
        }
        for (final Map<UUID, LeaderboardEntry> categoryEntries : entries.values()) {
            categoryEntries.clear();
        }

        final Set<UUID> ids = new LinkedHashSet<>();
        for (final RealPlayerRegistry.RealPlayer player : realPlayers.all()) {
            ids.add(player.id());
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (realPlayers.isReal(player.getUniqueId())) {
                ids.add(player.getUniqueId());
            }
        }

        for (final UUID id : ids) {
            final OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
            final String name = displayName(id, offline);
            for (final LeaderboardCategory category : LeaderboardCategory.values()) {
                addEntry(category, id, name, score(category, offline));
            }
        }
        rankEntries();
    }

    /** Updates the cached rows for everyone who is online and re-ranks every category. */
    public void refreshOnlineRanking() {
        if (!settings.enabled()) {
            clearRanking();
            return;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final UUID id = player.getUniqueId();
            if (!realPlayers.isReal(id)) {
                continue;
            }
            final String name = displayName(id, player);
            for (final LeaderboardCategory category : LeaderboardCategory.values()) {
                addEntry(category, id, name, score(category, player));
            }
        }
        rankEntries();
    }

    private void clearRanking() {
        for (final Map<UUID, LeaderboardEntry> categoryEntries : entries.values()) {
            categoryEntries.clear();
        }
        for (final LeaderboardCategory category : LeaderboardCategory.values()) {
            rankings.put(category, List.of());
        }
        for (final LeaderboardDisplay display : displays.values()) {
            display.removeAll();
        }
    }

    private void rankEntries() {
        for (final LeaderboardCategory category : LeaderboardCategory.values()) {
            rankings.put(category, LeaderboardRanking.rank(
                    entries.get(category).values(), LeaderboardLayout.TOP_SIZE));
        }
    }

    /** Re-renders every board, for example after a nickname changed. */
    public void refresh() {
        if (!settings.enabled()) {
            return;
        }
        final Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (final LeaderboardDisplay display : displays.values()) {
            final LeaderboardCategory category = display.category();
            display.update(online, rankings.get(category));
        }
    }

    /**
     * A nickname change matters to every category for that player. Updating the cached name once
     * avoids rescanning every offline player for a cosmetic change.
     */
    public void nickChanged(final UUID id) {
        if (!settings.enabled()) {
            return;
        }
        if (entries.values().stream().anyMatch(categoryEntries -> categoryEntries.containsKey(id))) {
            final String name = displayName(id, Bukkit.getOfflinePlayer(id));
            for (final Map<UUID, LeaderboardEntry> categoryEntries : entries.values()) {
                final LeaderboardEntry existing = categoryEntries.get(id);
                if (existing != null) {
                    categoryEntries.put(id, new LeaderboardEntry(id, name, existing.value()));
                }
            }
            rankEntries();
        }
        refresh();
    }

    /** The current score for one category, used for the viewer's personal line. */
    long score(final LeaderboardCategory category, final OfflinePlayer player) {
        return switch (category) {
            case PLAYTIME -> stats.playtimeTicks(player);
            case DEATHS -> stats.deaths(player);
            case KILLS -> stats.playerKills(player);
            case AFK -> afk.afkTicks(player.getUniqueId());
            case VOTES -> votes.total(player.getUniqueId());
            case STREAK -> votes.bestStreak(player.getUniqueId());
        };
    }

    /** Kept for callers that only need playtime. */
    long playtimeTicks(final Player player) {
        return score(LeaderboardCategory.PLAYTIME, player);
    }

    List<LeaderboardEntry> rankingFor(final LeaderboardCategory category) {
        return rankings.get(category);
    }

    boolean canSee(final Player player) {
        return visible.test(player.getUniqueId());
    }

    SweatsPlugin plugin() {
        return plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        if (settings.enabled()) {
            updatePlayer(event.getPlayer());
        }
        refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        if (settings.enabled()) {
            updatePlayer(event.getPlayer());
        }
        for (final LeaderboardDisplay display : displays.values()) {
            display.hide(event.getPlayer());
        }
    }

    @EventHandler
    public void onWorldLoad(final WorldLoadEvent event) {
        boolean added = false;
        for (final LeaderboardStore.StoredDisplay stored : store.all()) {
            if (displays.containsKey(stored.id())
                    || !stored.world().equals(event.getWorld().getName())) {
                continue;
            }
            final Location live = stored.location();
            if (live != null) {
                displays.put(stored.id(), new LeaderboardDisplay(this, stored, live));
                added = true;
            }
        }
        if (added) {
            refresh();
        }
    }

    private void rebuildDisplays() {
        displays.clear();
        for (final LeaderboardStore.StoredDisplay stored : store.all()) {
            final Location live = stored.location();
            if (live != null) {
                displays.put(stored.id(), new LeaderboardDisplay(this, stored, live));
            }
        }
    }

    private void updatePlayer(final Player player) {
        final UUID id = player.getUniqueId();
        if (!realPlayers.isReal(id)) {
            return;
        }
        final String name = displayName(id, player);
        for (final LeaderboardCategory category : LeaderboardCategory.values()) {
            addEntry(category, id, name, score(category, player));
        }
        rankEntries();
    }

    private void addEntry(
            final LeaderboardCategory category,
            final UUID id,
            final String name,
            final long value) {
        if (value <= 0L || name == null || name.isBlank()) {
            entries.get(category).remove(id);
            return;
        }
        entries.get(category).put(id, new LeaderboardEntry(id, name, value));
    }

    private String displayName(final UUID id, final OfflinePlayer offline) {
        final String base = nicks.accountName(id)
                .orElseGet(() -> {
                    final String cached = realPlayers.name(id);
                    if (!cached.isBlank()) {
                        return cached;
                    }
                    final String name = offline == null ? null : offline.getName();
                    return name == null || name.isBlank() ? id.toString().substring(0, 8) : name;
                });
        return nicks.displayName(id, base);
    }

    private void startTasks() {
        stopTasks();
        if (!settings.enabled()) {
            return;
        }

        final long refreshTicks = settings.refreshSeconds() * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::refresh, refreshTicks, refreshTicks);

        final long rankingTicks = settings.rankingRefreshSeconds() * 20L;
        rankingTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::refreshOnlineRanking, rankingTicks, rankingTicks);
    }

    private void stopTasks() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (rankingTask != null) {
            rankingTask.cancel();
            rankingTask = null;
        }
    }
}
