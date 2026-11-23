package io.github.near.sweats.display;

import io.github.near.sweats.currency.CurrencyService;
import io.github.near.sweats.currency.CurrencyType;
import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.perks.PerkService;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.stats.PlayerProfile;
import io.github.near.sweats.stats.StatsService;
import io.github.near.sweats.teams.TeamService;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.Collection;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

final class PlayerDisplay {

    private static final String OBJECTIVE_NAME = "sweats";
    private static final String ENTRY_PREFIX = "sweats-line-";
    private static final String TEAM_PREFIX = "ranks";

    private final RankService ranks;
    private final StatsService stats;
    private final NickService nicks;
    private final PerkService perks;
    private final TeamService teams;
    private final CurrencyService currency;
    private final DisplaySettings settings;
    private final String playerName;
    private final Scoreboard sidebar;
    private final Score[] sidebarScores;
    private final Component[] renderedSidebar;
    private final Component tabListHeader;

    private Rank lastRank;
    private String lastDisplayName;
    private TextColor lastPlusColor;
    private boolean sidebarRendered;
    private Rank lastSidebarRank;
    private String lastSidebarTeam;
    private long lastCoin = Long.MIN_VALUE;
    private long lastShards = Long.MIN_VALUE;
    private boolean lastCurrencyLoaded;
    private long lastPlaytimeBucket = Long.MIN_VALUE;
    private int lastSidebarOnline = -1;
    private int lastMaxPlayers = -1;
    private int lastTeamVersion = -1;
    private int lastOnline = -1;
    private int lastPing = -1;

    PlayerDisplay(
            final Player player,
            final DisplaySettings settings,
            final StatsService stats,
            final RankService ranks,
            final NickService nicks,
            final PerkService perks,
            final TeamService teams,
            final CurrencyService currency) {
        this.ranks = ranks;
        this.stats = stats;
        this.nicks = nicks;
        this.perks = perks;
        this.teams = teams;
        this.currency = currency;
        this.settings = settings;
        this.playerName = player.getName();

        if (settings.scoreboardEnabled()) {
            final ScoreboardManager manager = Objects.requireNonNull(
                    Bukkit.getScoreboardManager(), "Scoreboard manager is not available");
            this.sidebar = manager.getNewScoreboard();
            final Objective objective = sidebar.registerNewObjective(
                    OBJECTIVE_NAME, Criteria.DUMMY, DisplayLayout.sidebarTitle(settings), RenderType.INTEGER);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(NumberFormat.blank());

            this.sidebarScores = new Score[settings.lines().size()];
            this.renderedSidebar = new Component[settings.lines().size()];
            for (int index = 0; index < sidebarScores.length; index++) {
                final Score score = objective.getScore(ENTRY_PREFIX + index);
                score.setScore(sidebarScores.length - index);
                sidebarScores[index] = score;
            }
            if (settings.defaultVisible()) {
                player.setScoreboard(sidebar);
            }
        } else {
            this.sidebar = null;
            this.sidebarScores = null;
            this.renderedSidebar = null;
        }

        this.tabListHeader = settings.tablistEnabled() ? DisplayLayout.tabListHeader(settings) : null;
    }

    void update(
            final Player player,
            final int online,
            final Collection<? extends Player> onlinePlayers) {
        final Rank rank = ranks.rankOf(player.getUniqueId());
        updateIdentity(player, rank);
        if (sidebar != null) {
            updateSidebar(player, online, rank);
            if (lastTeamVersion != ranks.version()) {
                rebuildTeams();
                for (final Player onlinePlayer : onlinePlayers) {
                    updateEntry(onlinePlayer.getName(), ranks.rankOf(onlinePlayer.getUniqueId()));
                }
            }
        }
        if (tabListHeader != null) {
            updateTabList(player, online);
        }
    }

    void updateRank(final Player player, final int online) {
        final Rank rank = ranks.rankOf(player.getUniqueId());
        updateIdentity(player, rank);
        if (sidebar != null) {
            updateSidebar(player, online, rank);
        }
    }

    void updateTabListOnly(final Player player, final int online) {
        updateIdentity(player, ranks.rankOf(player.getUniqueId()));
        if (tabListHeader != null) {
            updateTabList(player, online);
        }
    }

    void setSidebarVisible(final Player player, final boolean visible) {
        if (sidebar == null) {
            return;
        }
        if (visible) {
            player.setScoreboard(sidebar);
        } else if (player.getScoreboard() == sidebar) {
            final ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager != null) {
                player.setScoreboard(manager.getMainScoreboard());
            }
        }
    }

    void updateEntry(final String name, final Rank rank) {
        final Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        final Team team = mainBoard.getTeam(TEAM_PREFIX + rank.key());
        if (team != null && !team.hasEntry(name)) {
            team.addEntry(name);
        }
    }

    void removeEntry(final String name) {
        final Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (final Rank rank : ranks.ranks()) {
            final Team team = mainBoard.getTeam(TEAM_PREFIX + rank.key());
            if (team != null && team.hasEntry(name)) {
                team.removeEntry(name);
            }
        }
    }

    void clear(final Player player) {
        player.playerListName(null);
        player.setPlayerListOrder(0);
        if (sidebar != null && player.getScoreboard() == sidebar) {
            final ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager != null) {
                player.setScoreboard(manager.getMainScoreboard());
            }
        }
        if (tabListHeader != null) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        removeEntry(playerName);
    }

    private void updateIdentity(final Player player, final Rank rank) {
        final String displayName = nicks.displayName(player.getUniqueId(), playerName);
        final TextColor plusColor = perks.plusColor(player.getUniqueId());
        if (rank.equals(lastRank)
                && displayName.equals(lastDisplayName)
                && Objects.equals(plusColor, lastPlusColor)) {
            return;
        }
        lastRank = rank;
        lastDisplayName = displayName;
        lastPlusColor = plusColor;
        player.playerListName(rank.tabListName(displayName, plusColor));
        player.setPlayerListOrder(Math.max(0, rank.weight()));
    }

    private void updateSidebar(final Player player, final int online, final Rank rank) {
        final PlayerProfile profile = stats.profile(player);
        final TextColor plusColor = perks.plusColor(player.getUniqueId());
        final String teamName = teams.teamOf(player.getUniqueId())
                .map(TeamService.Team::name)
                .orElse("None");
        final boolean currencyLoaded = currency.loaded(player.getUniqueId());
        final long coins = currencyLoaded
                ? currency.balance(player.getUniqueId(), CurrencyType.SWEAT_COINS)
                : 0L;
        final long shards = currencyLoaded
                ? currency.balance(player.getUniqueId(), CurrencyType.TRYHARD_SHARDS)
                : 0L;
        final long playtimeBucket = Math.max(0L, profile.playtimeTicks()) / (20L * 60L);
        final int maxPlayers = Math.max(0, Bukkit.getMaxPlayers());

        if (sidebarRendered
                && rank.equals(lastSidebarRank)
                && teamName.equals(lastSidebarTeam)
                && currencyLoaded == lastCurrencyLoaded
                && coins == lastCoin
                && shards == lastShards
                && playtimeBucket == lastPlaytimeBucket
                && online == lastSidebarOnline
                && maxPlayers == lastMaxPlayers) {
            return;
        }

        sidebarRendered = true;
        lastSidebarRank = rank;
        lastSidebarTeam = teamName;
        lastCurrencyLoaded = currencyLoaded;
        lastCoin = coins;
        lastShards = shards;
        lastPlaytimeBucket = playtimeBucket;
        lastSidebarOnline = online;
        lastMaxPlayers = maxPlayers;

        final DisplaySnapshot snapshot = DisplaySnapshot.of(
                profile,
                rank,
                plusColor,
                teamName,
                coins,
                shards,
                currencyLoaded,
                online,
                maxPlayers);
        final Component[] lines = DisplayLayout.sidebar(snapshot, settings).toArray(Component[]::new);
        for (int index = 0; index < sidebarScores.length; index++) {
            final Component line = lines[index];
            if (!line.equals(renderedSidebar[index])) {
                sidebarScores[index].customName(line);
                renderedSidebar[index] = line;
            }
        }
    }

    private void rebuildTeams() {
        final Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (final Rank rank : ranks.ranks()) {
            final Team old = mainBoard.getTeam(TEAM_PREFIX + rank.key());
            if (old != null) {
                old.unregister();
            }
        }

        for (final Rank rank : ranks.ranks()) {
            final Team team = mainBoard.registerNewTeam(TEAM_PREFIX + rank.key());
            team.prefix(rank.displayPrefix());
            if (rank.color() instanceof final NamedTextColor named) {
                team.color(named);
            }
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        }
        lastTeamVersion = ranks.version();
    }

    private void updateTabList(final Player player, final int online) {
        final int ping = Math.max(0, player.getPing());
        if (online == lastOnline && ping == lastPing) {
            return;
        }

        lastOnline = online;
        lastPing = ping;
        player.sendPlayerListHeaderAndFooter(
                tabListHeader, DisplayLayout.tabListFooter(online, ping, settings));
    }
}
