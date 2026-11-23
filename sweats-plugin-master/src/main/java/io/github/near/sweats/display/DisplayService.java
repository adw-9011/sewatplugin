package io.github.near.sweats.display;

import io.github.near.sweats.SweatsPlugin;
import io.github.near.sweats.currency.CurrencyService;
import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.perks.PerkService;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.stats.StatsService;
import io.github.near.sweats.teams.TeamService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;

public final class DisplayService implements Listener {

    private static final long TABLIST_REFRESH_TICKS = 20L;

    private final SweatsPlugin plugin;
    private final StatsService stats;
    private final RankService ranks;
    private final NickService nicks;
    private final PerkService perks;
    private final TeamService teams;
    private final CurrencyService currency;
    private final Predicate<UUID> authenticated;
    private final Map<UUID, PlayerDisplay> displays = new HashMap<>();
    private final Map<UUID, Boolean> visibilityOverrides = new HashMap<>();

    private DisplaySettings settings;
    private BukkitTask refreshTask;
    private BukkitTask tabListTask;

    public DisplayService(
            final SweatsPlugin plugin,
            final StatsService stats,
            final RankService ranks,
            final NickService nicks,
            final PerkService perks,
            final TeamService teams,
            final CurrencyService currency,
            final Predicate<UUID> authenticated) {
        this.plugin = plugin;
        this.stats = stats;
        this.ranks = ranks;
        this.nicks = nicks;
        this.perks = perks;
        this.teams = teams;
        this.currency = currency;
        this.authenticated = authenticated;
        this.settings = DisplaySettings.from(plugin.getConfig());
        startRefreshTask();
        showAllOnline();
    }

    public void reloadSettings() {
        clearDisplays();
        this.settings = DisplaySettings.from(plugin.getConfig());
        startRefreshTask();
        showAllOnline();
    }

    public void clearState() {
        stopRefreshTask();
        clearDisplays();
        visibilityOverrides.clear();
    }

    private void clearDisplays() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final PlayerDisplay display = displays.get(player.getUniqueId());
            if (display != null) {
                display.clear(player);
            }
        }
        displays.clear();
    }

    /** Re-renders every online player after ranks or nicknames change. */
    public void refresh() {
        refreshAll();
    }

    public void nickChanged(final UUID id) {
        refreshAll();
    }

    /** Updates one player's identity and every viewer's nametag entry for that player. */
    public void rankChanged(final UUID id) {
        final Player player = Bukkit.getPlayer(id);
        if (player == null || !authenticated.test(id)) {
            return;
        }

        final Rank rank = ranks.rankOf(id);
        final Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        final Team team = mainBoard.getTeam("ranks" + rank.key());
        if (team != null && !team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
        final PlayerDisplay own = displays.get(id);
        if (own != null) {
            own.updateRank(player, visibleOnline(player, Bukkit.getOnlinePlayers()));
        }
    }

    /** Toggles only the caller's sidebar; the tablist display remains enabled. */
    public boolean toggleScoreboard(final Player player) {
        final UUID id = player.getUniqueId();
        if (!authenticated.test(id) || !settings.scoreboardEnabled()) {
            return false;
        }
        final PlayerDisplay display = displays.get(id);
        if (display == null) {
            return false;
        }
        final boolean visible = !scoreboardVisible(id);
        visibilityOverrides.put(id, visible);
        display.setSidebarVisible(player, visible);
        return visible;
    }

    public boolean scoreboardVisible(final UUID id) {
        return settings.scoreboardEnabled()
                && visibilityOverrides.getOrDefault(id, settings.defaultVisible());
    }

    public DisplaySettings settings() {
        return settings;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        show(player);

        if (displays.containsKey(player.getUniqueId())) {
            final Rank rank = ranks.rankOf(player.getUniqueId());
            final Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
            final Team team = mainBoard.getTeam("ranks" + rank.key());
            if (team != null) {
                team.addEntry(player.getName());
            }
        }
        refreshAll();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final String playerName = event.getPlayer().getName();
        for (final PlayerDisplay display : displays.values()) {
            display.removeEntry(playerName);
        }
        displays.remove(event.getPlayer().getUniqueId());
        visibilityOverrides.remove(event.getPlayer().getUniqueId());

        final List<? extends Player> remaining = Bukkit.getOnlinePlayers().stream()
                .filter(player -> !player.getUniqueId().equals(event.getPlayer().getUniqueId()))
                .toList();
        refreshAll(remaining);
    }

    private void showAllOnline() {
        final Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (final Player player : online) {
            show(player);
        }
        refreshAll(online);
    }

    /** Called after authentication succeeds, so no display is sent before login. */
    public void showPlayer(final Player player) {
        if (!player.isOnline()) {
            return;
        }
        show(player);
        if (displays.containsKey(player.getUniqueId())) {
            final Rank rank = ranks.rankOf(player.getUniqueId());
            final Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
            final Team team = mainBoard.getTeam("ranks" + rank.key());
            if (team != null) {
                team.addEntry(player.getName());
            }
            refreshAll();
        }
    }

    /** Called when a player logs out or is unregistered. */
    public void hidePlayer(final Player player) {
        final UUID id = player.getUniqueId();
        final PlayerDisplay display = displays.remove(id);
        if (display != null) {
            display.clear(player);
        }
        visibilityOverrides.remove(id);
        for (final PlayerDisplay other : displays.values()) {
            other.removeEntry(player.getName());
        }
    }

    private void show(final Player player) {
        if (!authenticated.test(player.getUniqueId())) {
            return;
        }
        if (!settings.scoreboardEnabled() && !settings.tablistEnabled()) {
            return;
        }
        final UUID id = player.getUniqueId();
        final PlayerDisplay display = displays.computeIfAbsent(
                id,
                ignored -> new PlayerDisplay(
                        player, settings, stats, ranks, nicks, perks, teams, currency));
        display.setSidebarVisible(player, scoreboardVisible(id));
    }

    private void refreshAll() {
        refreshAll(Bukkit.getOnlinePlayers());
    }

    private void refreshAll(final Collection<? extends Player> online) {
        for (final Player player : online) {
            final PlayerDisplay display = displays.get(player.getUniqueId());
            if (display != null) {
                display.update(player, visibleOnline(player, online), online);
            }
        }
    }

    /** Count players visible to this viewer so vanished players stay out of ordinary views. */
    private static int visibleOnline(final Player viewer, final Collection<? extends Player> online) {
        int count = 0;
        for (final Player other : online) {
            if (viewer.canSee(other)) {
                count++;
            }
        }
        return count;
    }

    private void startRefreshTask() {
        stopRefreshTask();
        if (settings.scoreboardEnabled()) {
            final long interval = settings.refreshTicks();
            refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> refreshAll(), interval, interval);
        }
        if (settings.tablistEnabled()
                && (!settings.scoreboardEnabled() || settings.refreshTicks() != TABLIST_REFRESH_TICKS)) {
            tabListTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::refreshTabLists, TABLIST_REFRESH_TICKS, TABLIST_REFRESH_TICKS);
        }
    }

    private void refreshTabLists() {
        final Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (final Player player : online) {
            final PlayerDisplay display = displays.get(player.getUniqueId());
            if (display != null) {
                display.updateTabListOnly(player, visibleOnline(player, online));
            }
        }
    }

    private void stopRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (tabListTask != null) {
            tabListTask.cancel();
            tabListTask = null;
        }
    }
}
