package io.github.near.sweats;

import io.github.near.sweats.afk.AfkService;
import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.chat.MentionHighlighter;
import io.github.near.sweats.chat.OnlinePlayerNames;
import io.github.near.sweats.chat.EmojiChatListener;
import io.github.near.sweats.chat.RankChatListener;
import io.github.near.sweats.commands.BanCommand;
import io.github.near.sweats.commands.ChangePasswordCommand;
import io.github.near.sweats.commands.EmojiCommand;
import io.github.near.sweats.commands.GrantCommand;
import io.github.near.sweats.commands.HistoryCommand;
import io.github.near.sweats.commands.HomeCommand;
import io.github.near.sweats.commands.HomeSetCommand;
import io.github.near.sweats.commands.KickCommand;
import io.github.near.sweats.commands.LeaderboardCommand;
import io.github.near.sweats.commands.ListCommand;
import io.github.near.sweats.commands.LoginCommand;
import io.github.near.sweats.commands.LogoutCommand;
import io.github.near.sweats.commands.MapArtCommand;
import io.github.near.sweats.commands.MaintenanceCommand;
import io.github.near.sweats.commands.MemberChatCommand;
import io.github.near.sweats.commands.MuteCommand;
import io.github.near.sweats.commands.NickCommand;
import io.github.near.sweats.commands.PingCommand;
import io.github.near.sweats.commands.PlusColorCommand;
import io.github.near.sweats.commands.PremiumCommand;
import io.github.near.sweats.commands.RamCommand;
import io.github.near.sweats.commands.RankCommand;
import io.github.near.sweats.commands.RegisterCommand;
import io.github.near.sweats.commands.RuneCommand;
import io.github.near.sweats.commands.ScareCommand;
import io.github.near.sweats.commands.ShowCommand;
import io.github.near.sweats.commands.StatsCommand;
import io.github.near.sweats.commands.SweatsCommand;
import io.github.near.sweats.commands.TeamAcceptCommand;
import io.github.near.sweats.commands.TeamChatCommand;
import io.github.near.sweats.commands.TeamCommand;
import io.github.near.sweats.commands.TeamDenyCommand;
import io.github.near.sweats.commands.TpacceptCommand;
import io.github.near.sweats.commands.TpcancelCommand;
import io.github.near.sweats.commands.TpdenyCommand;
import io.github.near.sweats.commands.TpaCommand;
import io.github.near.sweats.commands.UnbanCommand;
import io.github.near.sweats.commands.UnmuteCommand;
import io.github.near.sweats.commands.UnvanishCommand;
import io.github.near.sweats.commands.VanishCommand;
import io.github.near.sweats.config.ConfigUpdater;
import io.github.near.sweats.currency.CurrencyCommand;
import io.github.near.sweats.currency.CurrencyService;
import io.github.near.sweats.logging.discord.DiscordLogService;
import io.github.near.sweats.display.DisplayService;
import io.github.near.sweats.dupe.DupeDetector;
import io.github.near.sweats.enderchest.EnderChestService;
import io.github.near.sweats.homes.HomeService;
import io.github.near.sweats.friends.FriendsService;
import io.github.near.sweats.menu.MenuGuard;
import io.github.near.sweats.pm.BukkitMessageDirectory;
import io.github.near.sweats.pm.MessageCommand;
import io.github.near.sweats.pm.MessagingService;
import io.github.near.sweats.commands.FriendCommand;
import io.github.near.sweats.leaderboard.LeaderboardService;
import io.github.near.sweats.mapart.MapArtService;
import io.github.near.sweats.maintenance.MaintenanceService;
import io.github.near.sweats.moderation.ModerationService;
import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.perks.PerkService;
import io.github.near.sweats.players.RealPlayerRegistry;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.runes.RuneAccessStore;
import io.github.near.sweats.runes.RuneDrops;
import io.github.near.sweats.runes.RuneEffects;
import io.github.near.sweats.runes.RuneGui;
import io.github.near.sweats.runes.RuneItems;
import io.github.near.sweats.runes.RunePackSender;
import io.github.near.sweats.runes.RuneTable;
import io.github.near.sweats.scare.ScareService;
import io.github.near.sweats.show.ShowService;
import io.github.near.sweats.stats.StatsMenuListener;
import io.github.near.sweats.stats.StatsService;
import io.github.near.sweats.teams.TeamService;
import io.github.near.sweats.tpa.TpaService;
import io.github.near.sweats.vanish.VanishService;
import io.github.near.sweats.vote.VoteService;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Entry point for the Sweats plugin.
 *
 * <p>Powers {@code /show} (display held/worn items with the vanilla tooltip), chat mention
 * highlighting (online player names light up inside chat messages), {@code /scare} (admin
 * jumpscares built from titles, sounds, particles and post effects), {@code /gif} (images from
 * the plugin folder handed out as mapart items), {@code /stats} (a chest menu with a player's
 * statistics), {@code /ram} (JVM heap usage), {@code /ping} (player latency), ranks, permissions,
 * moderation, the per-player scoreboard and the tablist header/footer.
 */
public final class SweatsPlugin extends JavaPlugin {

    private ShowService showService;
    private OnlinePlayerNames onlinePlayerNames;
    private MentionHighlighter mentionHighlighter;
    private EmojiChatListener emojiChatListener;
    private PingCommand pingCommand;
    private ScareService scareService;
    private MapArtService mapArtService;
    private StatsService statsService;
    private RankService rankService;
    private PermissionService permissionService;
    private PerkService perkService;
    private NickService nickService;
    private ModerationService moderationService;
    private MaintenanceService maintenanceService;
    private AuthService authService;
    private DiscordLogService discordService;
    private MessagingService messagingService;
    private CurrencyService currencyService;
    private DupeDetector dupeDetector;
    private DisplayService displayService;
    private RealPlayerRegistry realPlayers;
    private AfkService afkService;
    private LeaderboardService leaderboardService;
    private FriendsService friendsService;
    private HomeService homeService;
    private VanishService vanishService;
    private TeamService teamService;
    private TpaService tpaService;
    private EnderChestService enderChestService;
    private VoteService voteService;
    private RuneAccessStore runeAccess;
    private RuneItems runeItems;
    private RuneGui runeGui;
    private RuneTable runeTable;
    private RuneDrops runeDrops;
    private RuneEffects runeEffects;
    private BukkitTask rankExpiryTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        applyConfigUpdate();

        final Path folder = getDataFolder().toPath();

        this.rankService = new RankService(folder, getLogger()::warning);
        getServer().getPluginManager().registerEvents(rankService, this);
        this.perkService = new PerkService(folder, rankService, getLogger()::warning);
        this.permissionService =
                new PermissionService(this, folder, rankService, getLogger()::warning);
        getServer().getPluginManager().registerEvents(permissionService, this);

        this.showService = new ShowService(this);
        getServer().getPluginManager().registerEvents(showService, this);

        this.onlinePlayerNames = new OnlinePlayerNames();
        onlinePlayerNames.initialize();
        getServer().getPluginManager().registerEvents(onlinePlayerNames, this);
        this.mentionHighlighter = new MentionHighlighter(this, onlinePlayerNames);
        getServer().getPluginManager().registerEvents(mentionHighlighter, this);
        this.emojiChatListener = new EmojiChatListener(this, rankService);
        getServer().getPluginManager().registerEvents(emojiChatListener, this);
        this.pingCommand = new PingCommand(this, onlinePlayerNames);

        this.scareService = new ScareService(this);
        getServer().getPluginManager().registerEvents(scareService, this);

        this.mapArtService = new MapArtService(this);

        this.statsService = new StatsService(this);
        getServer().getPluginManager().registerEvents(statsService, this);
        getServer().getPluginManager().registerEvents(new StatsMenuListener(), this);

        this.nickService = new NickService(folder, getLogger()::warning);
        getServer().getPluginManager().registerEvents(nickService, this);
        this.moderationService = new ModerationService(this, folder, getLogger()::warning);
        getServer().getPluginManager().registerEvents(moderationService, this);
        getServer().getPluginManager().registerEvents(
                new RankChatListener(rankService, nickService, perkService), this);

        // Before auth: the maintenance gate rejects a connection before the login gate sees it.
        this.maintenanceService =
                new MaintenanceService(this, folder, getLogger()::warning);
        getServer().getPluginManager().registerEvents(maintenanceService, this);

        // Auth has to exist before the display service so joining players never receive a
        // scoreboard or tablist entry before they log in.
        this.authService = new AuthService(this);
        getServer().getPluginManager().registerEvents(authService, this);

        // Discord logging is best-effort and never blocks gameplay: every webhook send is queued.
        this.discordService = new DiscordLogService(this, getLogger()::warning);
        getServer().getPluginManager().registerEvents(discordService, this);

        this.messagingService = new MessagingService(
                this, rankService, nickService, perkService, new BukkitMessageDirectory(),
                permissionService, authService::isAuthenticated,
                discordService::logPrivateMessage, getLogger()::warning);
        authService.addDeauthListener(player -> messagingService.clear(player.getUniqueId()));

        this.currencyService = new CurrencyService(
                this, folder, authService::isAuthenticated, getLogger()::warning);
        try {
            currencyService.open();
        } catch (final Exception exception) {
            getLogger().severe("Could not open currency.db: " + exception.getMessage());
        }
        getServer().getPluginManager().registerEvents(currencyService, this);
        authService.addAuthListener(currencyService::onAuthenticated);
        authService.addDeauthListener(currencyService::onDeauthenticated);
        currencyService.start();

        // Only confirmed accounts and real joins may appear on leaderboards. NPC/shop profiles in
        // the server's user cache are never added to this registry.
        this.realPlayers = new RealPlayerRegistry(folder, getLogger()::warning);
        realPlayers.seed(authService.store().allAccounts().stream()
                .map(account -> new RealPlayerRegistry.Identity(account.uuid(), account.lastName()))
                .toList());
        getServer().getPluginManager().registerEvents(realPlayers, this);

        this.afkService = new AfkService(this);
        getServer().getPluginManager().registerEvents(afkService, this);

        // The embedded Votifier receiver replaces the standalone plugin and needs the port free.
        this.voteService = new VoteService(this, folder, realPlayers);
        voteService.start();

        this.teamService = new TeamService(folder, rankService, getLogger()::warning);
        getServer().getPluginManager().registerEvents(teamService, this);
        teamService.reloadSettings(
                getConfig().getInt("teams.base-size", 4),
                getConfig().getInt("teams.invite-seconds", 60));

        this.displayService =
                new DisplayService(
                        this,
                        statsService,
                        rankService,
                        nickService,
                        perkService,
                        teamService,
                        currencyService,
                        authService::isAuthenticated);
        getServer().getPluginManager().registerEvents(displayService, this);

        // The leaderboards are per viewer and must not appear before a player has logged in.
        this.leaderboardService =
                new LeaderboardService(
                        this, statsService, nickService, realPlayers, afkService,
                        voteService.votes(), authService::isAuthenticated);
        getServer().getPluginManager().registerEvents(leaderboardService, this);
        nickService.addChangeListener(leaderboardService::nickChanged);
        // A vote can move an offline player up the votes/streak boards, so re-rank immediately.
        voteService.addListener(leaderboardService::refreshRanking);
        // Show the boards as soon as a player logs in, and drop them again on logout.
        authService.addAuthListener(player -> leaderboardService.refresh());
        authService.addDeauthListener(player -> leaderboardService.refresh());

        this.vanishService = new VanishService(
                this, rankService, authService::isAuthenticated, onlinePlayerNames);
        getServer().getPluginManager().registerEvents(vanishService, this);

        authService.addAuthListener(displayService::showPlayer);
        authService.addDeauthListener(displayService::hidePlayer);
        // After the display listener, so hiding a vanished player cannot be undone by showPlayer.
        authService.addAuthListener(vanishService::onAuthenticated);
        authService.addDeauthListener(vanishService::onDeauthenticated);
        nickService.addChangeListener(displayService::nickChanged);

        this.homeService = new HomeService(
                this, folder, rankService, authService::isAuthenticated, getLogger()::warning);
        getServer().getPluginManager().registerEvents(homeService, this);

        this.friendsService = new FriendsService(
                this, folder, rankService, permissionService,
                authService::isAuthenticated, getLogger()::warning, vanishService);
        getServer().getPluginManager().registerEvents(friendsService, this);
        authService.addAuthListener(friendsService::onAuthenticated);
        authService.addDeauthListener(friendsService::onDeauthenticated);
        authService.addAccountDeletedListener(friendsService::deletePlayerData);

        this.tpaService = new TpaService(this, rankService, authService::isAuthenticated, vanishService);
        getServer().getPluginManager().registerEvents(tpaService, this);

        this.enderChestService = new EnderChestService(
                folder, rankService, authService::isAuthenticated, getLogger()::warning);
        getServer().getPluginManager().registerEvents(enderChestService, this);

        // Cosmetic runes: browser, Runic Table, boss drops, effects and the resource pack.
        this.runeAccess = new RuneAccessStore(folder, getLogger()::warning);
        this.runeItems = new RuneItems(this);
        this.runeGui = new RuneGui(this, runeItems, permissionService);
        this.runeTable = new RuneTable(this, runeGui, runeAccess, permissionService);
        runeGui.setTableAccess(runeTable::canUse);
        this.runeDrops = new RuneDrops(this, runeItems, runeAccess);
        this.runeEffects = new RuneEffects(this, runeItems);
        getServer().getPluginManager().registerEvents(runeGui, this);
        getServer().getPluginManager().registerEvents(runeTable, this);
        getServer().getPluginManager().registerEvents(runeDrops, this);
        getServer().getPluginManager().registerEvents(runeEffects, this);
        getServer().getPluginManager().registerEvents(new RunePackSender(this), this);
        runeTable.registerRecipe();
        runeTable.startMaintenance();

        // Duplication evidence is detection-only; the menu guard hardens every custom menu.
        this.dupeDetector = new DupeDetector(this, discordService, getLogger()::warning);
        getServer().getPluginManager().registerEvents(dupeDetector, this);
        getServer().getPluginManager().registerEvents(
                new MenuGuard(dupeDetector::reportSuspicious), this);

        rankService.addChangeListener(permissionService::rankChanged);
        rankService.addChangeListener(displayService::rankChanged);
        rankService.addChangeListener(enderChestService::rankChanged);
        rankService.addRankListListener(permissionService::rankListChanged);
        rankService.addRankListListener(displayService::refresh);
        perkService.addChangeListener(displayService::rankChanged);
        permissionService.applyAll();

        // Expired temporary ranks are reset on the main thread so listeners may safely touch
        // scoreboards and menus; ordinary reads already treat them as Default in the meantime.
        this.rankExpiryTask = getServer().getScheduler().runTaskTimer(
                this, rankService::expireAssignments, 20L * 60L, 20L * 60L);

        registerCommands();

        getLogger().info("Sweats enabled - commands, ranks, perks, homes, teams, teleport "
                + "requests, vanish, permissions, moderation, scoreboard, tablist, chat mentions, "
                + "chat emojis, maintenance mode, themed leaderboards, AFK tracking, runes and "
                + "the Runic Table, boss rune drops, the login gate and the Votifier listener "
                + "are ready.");
    }

    @Override
    public void onDisable() {
        if (voteService != null) {
            voteService.stop();
        }
        if (afkService != null) {
            afkService.clearState();
        }
        if (realPlayers != null) {
            realPlayers.save();
        }
        if (maintenanceService != null) {
            maintenanceService.close();
        }
        if (enderChestService != null) {
            enderChestService.close();
        }
        if (homeService != null) {
            homeService.close();
        }
        if (friendsService != null) {
            friendsService.close();
        }
        if (teamService != null) {
            teamService.close();
        }
        if (tpaService != null) {
            tpaService.close();
        }
        if (vanishService != null) {
            vanishService.close();
        }
        if (perkService != null) {
            perkService.close();
        }
        if (statsService != null) {
            statsService.save();
        }
        if (rankExpiryTask != null) {
            rankExpiryTask.cancel();
            rankExpiryTask = null;
        }
        if (rankService != null) {
            rankService.close();
        }
        if (showService != null) {
            showService.clearState();
        }
        if (scareService != null) {
            scareService.clearState();
        }
        if (mapArtService != null) {
            mapArtService.clearState();
        }
        if (displayService != null) {
            displayService.clearState();
        }
        if (leaderboardService != null) {
            leaderboardService.clearState();
        }
        if (authService != null) {
            authService.close();
        }
        if (permissionService != null) {
            permissionService.clearState();
        }
        if (runeEffects != null) {
            runeEffects.close();
        }
        if (runeDrops != null) {
            runeDrops.close();
        }
        if (runeTable != null) {
            runeTable.close();
        }
        if (runeAccess != null) {
            runeAccess.close();
        }
        if (dupeDetector != null) {
            dupeDetector.close();
        }
        if (currencyService != null) {
            currencyService.close();
        }
        if (discordService != null) {
            discordService.close();
        }
    }

    /** Re-reads config.yml and pushes the new values into the running services. */
    public void reload() {
        applyConfigUpdate();
        reloadConfig();
        showService.reloadSettings();
        mentionHighlighter.reload();
        emojiChatListener.reload();
        pingCommand.reloadSettings();
        scareService.reloadSettings();
        mapArtService.reloadSettings();
        rankService.reload();
        perkService.reload();
        permissionService.reload();
        nickService.reload();
        moderationService.reload();
        maintenanceService.reload();
        authService.reload();
        homeService.reload();
        friendsService.reload();
        teamService.reloadSettings(
                getConfig().getInt("teams.base-size", 4),
                getConfig().getInt("teams.invite-seconds", 60));
        tpaService.reloadSettings();
        enderChestService.reload();
        displayService.reloadSettings();
        afkService.reload();
        leaderboardService.reloadSettings();
        voteService.reload();
        runeAccess.reload();
        runeTable.registerRecipe();
        discordService.reload();
        messagingService.reload();
        currencyService.reload();
        dupeDetector.reload();
    }

    private void registerCommands() {
        final PluginCommand emoji = Objects.requireNonNull(
                getCommand("emoji"), "Command 'emoji' is missing from plugin.yml");
        final EmojiCommand emojiCommand = new EmojiCommand();
        emoji.setExecutor(emojiCommand);
        emoji.setTabCompleter(emojiCommand);

        final PluginCommand scare = Objects.requireNonNull(
                getCommand("scare"), "Command 'scare' is missing from plugin.yml");
        final ScareCommand scareCommand = new ScareCommand(scareService, permissionService);
        scare.setExecutor(scareCommand);
        scare.setTabCompleter(scareCommand);

        final PluginCommand gif = Objects.requireNonNull(
                getCommand("gif"), "Command 'gif' is missing from plugin.yml");
        final MapArtCommand mapArtCommand = new MapArtCommand(this, mapArtService, permissionService);
        gif.setExecutor(mapArtCommand);
        gif.setTabCompleter(mapArtCommand);

        final PluginCommand show = Objects.requireNonNull(
                getCommand("show"), "Command 'show' is missing from plugin.yml");
        final ShowCommand showCommand = new ShowCommand(showService, permissionService);
        show.setExecutor(showCommand);
        show.setTabCompleter(showCommand);

        final PluginCommand sweats = Objects.requireNonNull(
                getCommand("sweats"), "Command 'sweats' is missing from plugin.yml");
        final SweatsCommand sweatsCommand = new SweatsCommand(this);
        sweats.setExecutor(sweatsCommand);
        sweats.setTabCompleter(sweatsCommand);

        final PluginCommand stats = Objects.requireNonNull(
                getCommand("stats"), "Command 'stats' is missing from plugin.yml");
        final StatsCommand statsCommand = new StatsCommand(statsService, permissionService);
        stats.setExecutor(statsCommand);
        stats.setTabCompleter(statsCommand);

        final PluginCommand ram = Objects.requireNonNull(
                getCommand("ram"), "Command 'ram' is missing from plugin.yml");
        ram.setExecutor(new RamCommand());

        final PluginCommand ping = Objects.requireNonNull(
                getCommand("ping"), "Command 'ping' is missing from plugin.yml");
        ping.setExecutor(pingCommand);
        ping.setTabCompleter(pingCommand);

        final PluginCommand rank = Objects.requireNonNull(
                getCommand("rank"), "Command 'rank' is missing from plugin.yml");
        final RankCommand rankCommand = new RankCommand(rankService, permissionService);
        rank.setExecutor(rankCommand);
        rank.setTabCompleter(rankCommand);

        final PluginCommand nick = Objects.requireNonNull(
                getCommand("nick"), "Command 'nick' is missing from plugin.yml");
        final NickCommand nickCommand = new NickCommand(nickService, permissionService);
        nick.setExecutor(nickCommand);
        nick.setTabCompleter(nickCommand);

        final PluginCommand grant = Objects.requireNonNull(
                getCommand("grant"), "Command 'grant' is missing from plugin.yml");
        final GrantCommand grantCommand = new GrantCommand(permissionService, rankService);
        grant.setExecutor(grantCommand);
        grant.setTabCompleter(grantCommand);

        final PluginCommand leaderboard = Objects.requireNonNull(
                getCommand("leaderboard"), "Command 'leaderboard' is missing from plugin.yml");
        final LeaderboardCommand leaderboardCommand =
                new LeaderboardCommand(leaderboardService, permissionService);
        leaderboard.setExecutor(leaderboardCommand);
        leaderboard.setTabCompleter(leaderboardCommand);

        final PluginCommand ban = Objects.requireNonNull(
                getCommand("ban"), "Command 'ban' is missing from plugin.yml");
        final BanCommand banCommand =
                new BanCommand(moderationService, permissionService, rankService);
        ban.setExecutor(banCommand);
        ban.setTabCompleter(banCommand);

        final PluginCommand kick = Objects.requireNonNull(
                getCommand("kick"), "Command 'kick' is missing from plugin.yml");
        final KickCommand kickCommand =
                new KickCommand(moderationService, permissionService, rankService);
        kick.setExecutor(kickCommand);
        kick.setTabCompleter(kickCommand);

        final PluginCommand mute = Objects.requireNonNull(
                getCommand("mute"), "Command 'mute' is missing from plugin.yml");
        final MuteCommand muteCommand =
                new MuteCommand(moderationService, permissionService, rankService);
        mute.setExecutor(muteCommand);
        mute.setTabCompleter(muteCommand);

        final PluginCommand unban = Objects.requireNonNull(
                getCommand("unban"), "Command 'unban' is missing from plugin.yml");
        final UnbanCommand unbanCommand = new UnbanCommand(moderationService, permissionService);
        unban.setExecutor(unbanCommand);
        unban.setTabCompleter(unbanCommand);

        final PluginCommand unmute = Objects.requireNonNull(
                getCommand("unmute"), "Command 'unmute' is missing from plugin.yml");
        final UnmuteCommand unmuteCommand =
                new UnmuteCommand(moderationService, permissionService);
        unmute.setExecutor(unmuteCommand);
        unmute.setTabCompleter(unmuteCommand);

        final PluginCommand history = Objects.requireNonNull(
                getCommand("history"), "Command 'history' is missing from plugin.yml");
        final HistoryCommand historyCommand =
                new HistoryCommand(moderationService, permissionService);
        history.setExecutor(historyCommand);
        history.setTabCompleter(historyCommand);

        final PluginCommand maintenance = Objects.requireNonNull(
                getCommand("maintenance"), "Command 'maintenance' is missing from plugin.yml");
        final MaintenanceCommand maintenanceCommand = new MaintenanceCommand(maintenanceService);
        maintenance.setExecutor(maintenanceCommand);
        maintenance.setTabCompleter(maintenanceCommand);

        final PluginCommand login = Objects.requireNonNull(
                getCommand("login"), "Command 'login' is missing from plugin.yml");
        login.setExecutor(new LoginCommand(authService));

        final PluginCommand register = Objects.requireNonNull(
                getCommand("register"), "Command 'register' is missing from plugin.yml");
        register.setExecutor(new RegisterCommand(authService));

        final PluginCommand logout = Objects.requireNonNull(
                getCommand("logout"), "Command 'logout' is missing from plugin.yml");
        logout.setExecutor(new LogoutCommand(authService));

        final PluginCommand changePass = Objects.requireNonNull(
                getCommand("changepass"), "Command 'changepass' is missing from plugin.yml");
        final ChangePasswordCommand changePasswordCommand =
                new ChangePasswordCommand(authService);
        changePass.setExecutor(changePasswordCommand);
        changePass.setTabCompleter(changePasswordCommand);

        final PluginCommand premium = Objects.requireNonNull(
                getCommand("premium"), "Command 'premium' is missing from plugin.yml");
        final PremiumCommand premiumCommand = new PremiumCommand(authService);
        premium.setExecutor(premiumCommand);
        premium.setTabCompleter(premiumCommand);

        final PluginCommand home = requiredCommand("home");
        home.setExecutor(new HomeCommand(homeService, permissionService, authService));

        final PluginCommand homeset = requiredCommand("homeset");
        final HomeSetCommand homeSetCommand =
                new HomeSetCommand(homeService, permissionService, authService);
        homeset.setExecutor(homeSetCommand);
        homeset.setTabCompleter(homeSetCommand);

        final PluginCommand vanish = requiredCommand("vanish");
        vanish.setExecutor(new VanishCommand(vanishService, permissionService, authService));

        final PluginCommand unvanish = requiredCommand("unvanish");
        unvanish.setExecutor(new UnvanishCommand(vanishService, permissionService, authService));

        final PluginCommand list = requiredCommand("list");
        list.setExecutor(new ListCommand(permissionService, authService));

        final TeamCommand teamCommand =
                new TeamCommand(teamService, permissionService, authService);
        for (final String name : List.of(
                "teamcreate", "teamdelete", "teaminvite", "teamlist",
                "teamleave", "teamkick", "teaminfo")) {
            final PluginCommand command = requiredCommand(name);
            command.setExecutor(teamCommand);
            command.setTabCompleter(teamCommand);
        }

        final PluginCommand teamAccept = requiredCommand("teamaccept");
        teamAccept.setExecutor(new TeamAcceptCommand(teamService, authService));

        final PluginCommand teamDeny = requiredCommand("teamdeny");
        teamDeny.setExecutor(new TeamDenyCommand(teamService, authService));

        final PluginCommand pc = requiredCommand("pc");
        pc.setExecutor(new TeamChatCommand(
                teamService, permissionService, authService, rankService, nickService,
                perkService, moderationService, discordService));

        final PluginCommand tpa = requiredCommand("tpa");
        final TpaCommand tpaCommand = new TpaCommand(tpaService, permissionService, authService);
        tpa.setExecutor(tpaCommand);
        tpa.setTabCompleter(tpaCommand);

        final PluginCommand tpaccept = requiredCommand("tpaccept");
        final TpacceptCommand tpacceptCommand =
                new TpacceptCommand(tpaService, permissionService, authService);
        tpaccept.setExecutor(tpacceptCommand);
        tpaccept.setTabCompleter(tpacceptCommand);

        final PluginCommand tpdeny = requiredCommand("tpdeny");
        final TpdenyCommand tpdenyCommand =
                new TpdenyCommand(tpaService, permissionService, authService);
        tpdeny.setExecutor(tpdenyCommand);
        tpdeny.setTabCompleter(tpdenyCommand);

        final PluginCommand tpcancel = requiredCommand("tpcancel");
        tpcancel.setExecutor(new TpcancelCommand(tpaService, permissionService, authService));

        final PluginCommand memberChat = requiredCommand("sweatschat");
        memberChat.setExecutor(new MemberChatCommand(
                permissionService, authService, rankService, nickService, perkService,
                moderationService));

        final PluginCommand plusColor = requiredCommand("pluscolor");
        final PlusColorCommand plusColorCommand =
                new PlusColorCommand(perkService, permissionService, authService);
        plusColor.setExecutor(plusColorCommand);
        plusColor.setTabCompleter(plusColorCommand);

        final RuneCommand runeCommand = new RuneCommand(
                this, runeItems, runeGui, runeTable, runeAccess, runeDrops, permissionService);
        for (final String name : List.of(
                "runes", "runetable", "adminsword", "runeluck",
                "runeaccess", "runepermdropchance")) {
            final PluginCommand command = requiredCommand(name);
            command.setExecutor(runeCommand);
            command.setTabCompleter(runeCommand);
        }

        final PluginCommand friend = requiredCommand("friend");
        final FriendCommand friendCommand = new FriendCommand(friendsService, permissionService);
        friend.setExecutor(friendCommand);
        friend.setTabCompleter(friendCommand);
        final PluginCommand f = getCommand("f");
        if (f != null) {
            f.setExecutor(friendCommand);
            f.setTabCompleter(friendCommand);
        }

        final PluginCommand currency = requiredCommand("currency");
        final CurrencyCommand currencyCommand = new CurrencyCommand(currencyService, rankService);
        currency.setExecutor(currencyCommand);
        currency.setTabCompleter(currencyCommand);

        final MessageCommand messageCommand = new MessageCommand(
                messagingService, authService, permissionService, moderationService);
        final PluginCommand msg = requiredCommand("msg");
        msg.setExecutor(messageCommand);
        msg.setTabCompleter(messageCommand);
        final PluginCommand reply = requiredCommand("r");
        reply.setExecutor(messageCommand);
        reply.setTabCompleter(messageCommand);

        // Also guard the dispatch path, not only PlayerCommandPreprocessEvent: another plugin can
        // call dispatchCommand without firing the preprocess event. The auth commands themselves
        // are allowed through by AuthService's allowlist.
        for (final String name : getDescription().getCommands().keySet()) {
            final PluginCommand command = getCommand(name);
            if (command != null && command.getExecutor() != null) {
                command.setExecutor(authService.commandGuard(command.getExecutor()));
            }
        }
    }

    private PluginCommand requiredCommand(final String name) {
        return Objects.requireNonNull(
                getCommand(name), "Command '" + name + "' is missing from plugin.yml");
    }

    /** The login gate, exposed for {@code /sweats auth} and tests. */
    public AuthService authService() {
        return authService;
    }

    /** The embedded Votifier listener, exposed for {@code /sweats vote}. */
    public VoteService voteService() {
        return voteService;
    }

    /** The per-player sidebar and tablist manager. */
    public DisplayService displayService() {
        return displayService;
    }

    /** Brings an older config.yml up to date with the settings this version understands. */
    private void applyConfigUpdate() {
        final ConfigUpdater.Update update = ConfigUpdater.update(this);
        if (update.changed()) {
            getLogger().info("config.yml updated for this version (added: " + names(update.added())
                    + "; removed: " + names(update.removed()) + "). Old copy: config.yml.bak");
        }
    }

    private static String names(final Set<String> keys) {
        return keys.isEmpty() ? "none" : String.join(", ", keys);
    }
}
