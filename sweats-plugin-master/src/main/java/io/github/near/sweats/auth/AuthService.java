package io.github.near.sweats.auth;

import io.github.near.sweats.SweatsPlugin;
import io.github.near.sweats.owner.Owner;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Login gate for the server.
 *
 * <p>The important part happens in {@link AsyncPlayerSpawnLocationEvent}: Paper lets a plugin
 * replace the location a joining player is about to be placed at, before the client is sent any
 * world data. Unauthenticated players are put in a safe login spot and their real position is
 * stored; it is restored only after a successful login. That closes the "join as someone else and
 * look at their base" hole that a freeze-only login plugin leaves open.
 *
 * <p>Passwords are typed in chat, not on the command line, when the player runs the command with
 * no arguments. Paper logs command arguments before plugins can see them, but it does not log
 * ordinary chat, and the chat event is cancelled before the message is broadcast.
 */
public final class AuthService implements Listener, AutoCloseable {

    private static final Set<String> ALLOWED_COMMANDS =
            Set.of("login", "l", "register", "premium");
    private static final long PREMIUM_CACHE_MILLIS = 10L * 60L * 1_000L;
    private static final long NOTICE_COOLDOWN_MILLIS = 1_000L;
    static final int EXECUTOR_QUEUE_CAPACITY = 128;
    static final int DB_QUEUE_CAPACITY = 256;
    private static final long SHUTDOWN_WAIT_SECONDS = 3L;
    /** Meta flag kept from the old bundled importer so an admin deletion is not undone. */
    private static final String LEGACY_IMPORT_FLAG = "import.bundled";
    private static final String LEGACY_FILE = "legacy-passwords.db";
    private static final String SIMPLELOGIN_IMPORT_FLAG = "import.simplelogin";

    private final SweatsPlugin plugin;
    private final Path folder;
    private final Consumer<String> warn;
    private final ExecutorService executor;
    private final ExecutorService databaseExecutor;
    private final AuthStore store;
    private final PremiumVerifier premiumVerifier = new PremiumVerifier();

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> internalTeleports = ConcurrentHashMap.newKeySet();
    private final Set<UUID> warnedAboutCommandPassword = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastReminder = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();
    private final Map<UUID, Long> premiumCache = new ConcurrentHashMap<>();
    private final Map<UUID, InetAddress> rawAddresses = new ConcurrentHashMap<>();
    private final List<Consumer<Player>> authListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Player>> deauthListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<UUID>> accountDeletedListeners = new CopyOnWriteArrayList<>();

    private volatile AuthConfig config;
    private volatile ProxyTrust proxyTrust;
    private volatile int cachedAccounts = -1;
    private volatile boolean available;
    private BukkitTask ticker;
    private volatile boolean closed;

    public AuthService(final SweatsPlugin plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath();
        this.warn = plugin.getLogger()::warning;
        this.config = AuthConfig.from(plugin.getConfig());
        this.proxyTrust = ProxyTrust.from(config.premiumTrustedProxies(), warn);
        this.executor = authExecutor();
        this.databaseExecutor = databaseExecutor();

        AuthStore opened = null;
        try {
            opened = new AuthStore(folder.resolve("auth.db"), warn);
            opened.open();
        } catch (final Exception exception) {
            plugin.getLogger().severe(
                    "Could not open auth.db - the login gate is failing closed and will reject "
                            + "all connections until the database is fixed: "
                            + exception.getMessage());
            if (opened != null) {
                opened.close();
            }
            opened = null;
        }
        this.store = opened;
        this.available = opened != null;
        reload();
        startTicker();
    }

    /**
     * One bounded worker for password hashing and other auth work. The single thread also caps
     * how much CPU an attacker can make the server spend on PBKDF2; the queue bounds latency and
     * turns overload into an explicit rejection instead of unbounded memory growth.
     */
    static ThreadPoolExecutor authExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                runnable -> {
                    final Thread thread = new Thread(runnable, "Sweats-Auth");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * One bounded worker for SQLite access.
     *
     * <p>This is deliberately separate from the hashing executor: PBKDF2 takes hundreds of
     * milliseconds, and a single shared thread would queue every session lookup and pending
     * location behind it. The database worker only performs short serialized queries, matching
     * the single-connection {@link AuthStore}. Its queue is bounded so a flood of joins turns
     * into an explicit "busy" answer instead of unbounded memory growth.
     */
    static ThreadPoolExecutor databaseExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DB_QUEUE_CAPACITY),
                runnable -> {
                    final Thread thread = new Thread(runnable, "Sweats-AuthDB");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle and configuration

    public void reload() {
        this.config = AuthConfig.from(plugin.getConfig());
        this.proxyTrust = ProxyTrust.from(config.premiumTrustedProxies(), warn);
        this.premiumCache.clear();
        if (config.premiumEnabled()
                && config.premiumMode() == AuthConfig.PremiumMode.PROXY
                && !config.premiumVerifyMojang()
                && proxyTrust.isEmpty()) {
            warn.accept("auth.premium.mode is PROXY but auth.premium.trusted-proxies is empty; "
                    + "the passwordless proxy bypass stays disabled until the proxy address is "
                    + "listed. Players can still log in with /login or /premium.");
        }

        if (!config.enabled()) {
            releaseWaiting();
            return;
        }
        if (store == null) {
            plugin.getLogger().severe(
                    "Auth is enabled but auth.db is unavailable; keeping the login gate closed.");
            return;
        }

        final long now = System.currentTimeMillis();
        final long throttleCutoff = now - Math.max(
                300_000L,
                Math.max(config.attemptCooldownMillis(), config.registerCooldownMillis()) * 2L);
        final boolean importLegacy = config.importSimpleLogin();
        runDb(() -> {
            store.clearExpiredSessions(now);
            store.clearStaleThrottles(throttleCutoff);
            if (importLegacy) {
                importLegacyOnce(now);
            }
            cachedAccounts = store.countAccounts();
        });
    }

    /**
     * Copies legacy accounts from operator-supplied databases at most once.
     *
     * <p>Credential databases are never bundled with the plugin: operators may place an old
     * database at {@code plugins/Sweats/legacy-passwords.db} themselves, and the external
     * SimpleLogin path remains supported. The meta flags stop a later start from recreating an
     * account an admin deliberately removed with {@code /sweats auth unregister}.
     */
    private int importLegacyOnce(final long now) {
        int total = 0;

        if (!store.flag(LEGACY_IMPORT_FLAG)) {
            final Path manual = folder.resolve(LEGACY_FILE);
            if (Files.isRegularFile(manual)) {
                final int imported = store.importSimpleLogin(manual, now);
                if (imported >= 0) {
                    store.setMeta(LEGACY_IMPORT_FLAG, "true");
                    if (imported > 0) {
                        plugin.getLogger().info("Imported " + imported
                                + " legacy account(s) from " + manual + ".");
                    }
                    total += imported;
                }
            }
        }

        if (!store.flag(SIMPLELOGIN_IMPORT_FLAG)) {
            final Path legacy = simpleLoginPath();
            if (Files.isRegularFile(legacy)) {
                final int imported = store.importSimpleLogin(legacy, now);
                if (imported >= 0) {
                    store.setMeta(SIMPLELOGIN_IMPORT_FLAG, "true");
                    if (imported > 0) {
                        plugin.getLogger().info("Imported " + imported
                                + " SimpleLogin account(s) from " + legacy + ".");
                    }
                    total += imported;
                }
            }
        }

        return total;
    }

    /**
     * Forced import used by {@code /sweats auth import}: reads both operator-supplied locations
     * without consulting the once-only flags. Runs on the database worker.
     */
    private int importAllLegacy(final long now) {
        int total = 0;
        boolean found = false;

        final Path manual = folder.resolve(LEGACY_FILE);
        if (Files.isRegularFile(manual)) {
            final int imported = store.importSimpleLogin(manual, now);
            if (imported < 0) {
                return -1;
            }
            total += imported;
            found = true;
        }

        final Path simple = simpleLoginPath();
        if (Files.isRegularFile(simple)) {
            final int imported = store.importSimpleLogin(simple, now);
            if (imported < 0) {
                return -1;
            }
            total += imported;
            found = true;
        }

        return found ? total : 0;
    }

    @Override
    public void close() {
        closed = true;
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        executor.shutdownNow();
        databaseExecutor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                warn.accept("An auth task did not stop within "
                        + SHUTDOWN_WAIT_SECONDS + " seconds; closing auth.db anyway.");
            }
            if (!databaseExecutor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                warn.accept("An auth database task did not stop within "
                        + SHUTDOWN_WAIT_SECONDS + " seconds; closing auth.db anyway.");
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (store != null) {
            store.close();
        }
        authenticated.clear();
        prompts.clear();
        joinTimes.clear();
        lastReminder.clear();
        lastNotice.clear();
        premiumCache.clear();
        internalTeleports.clear();
        warnedAboutCommandPassword.clear();
        rawAddresses.clear();
    }

    /** Lets everyone through when auth is switched off by a reload. */
    private void releaseWaiting() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final UUID id = player.getUniqueId();
            if (!authenticated.add(id)) {
                continue;
            }
            prompts.remove(id);
            player.setInvulnerable(false);
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.updateCommands();
            for (final Player other : Bukkit.getOnlinePlayers()) {
                player.showPlayer(plugin, other);
                other.showPlayer(plugin, player);
            }
            releasePending(player);
            notifyAuthenticated(player);
        }
    }

    /** Restores the stored location when auth is switched off, without blocking the main thread. */
    private void releasePending(final Player player) {
        final UUID id = player.getUniqueId();
        if (store == null) {
            teleportTo(player, player.getLocation());
            return;
        }
        query(null, () -> {
            final Optional<AuthStore.StoredLocation> pending = store.peekPending(id);
            if (pending.isPresent()) {
                store.deletePending(id);
            }
            return pending;
        }, pending -> {
            if (!player.isOnline()) {
                return;
            }
            final Location target = pending.map(this::toLocation).orElse(null);
            teleportTo(player, target != null ? target : player.getLocation());
        });
    }

    /** True when the login gate is supposed to be doing its job. */
    public boolean isActive() {
        return !closed && config.enabled();
    }

    /**
     * True when the gate is enabled but its database is missing. The user-facing behaviour is to
     * reject connections instead of silently turning the gate off.
     */
    public boolean isUnavailable() {
        return isActive() && store == null;
    }

    /**
     * Used by the scoreboard/tablist: a player must not receive statistics or a rank sidebar
     * before logging in, because it would reveal that they are on their real account.
     */
    public boolean isAuthenticated(final UUID uuid) {
        return !isActive() || authenticated.contains(uuid);
    }

    public AuthConfig config() {
        return config;
    }

    public AuthStore store() {
        return store;
    }

    public void addAuthListener(final Consumer<Player> listener) {
        authListeners.add(listener);
    }

    public void addDeauthListener(final Consumer<Player> listener) {
        deauthListeners.add(listener);
    }

    /**
     * Registers a callback that removes any other per-account data when an admin deletes an
     * account. The listener receives the deleted UUID on the server thread.
     */
    public void addAccountDeletedListener(final Consumer<UUID> listener) {
        accountDeletedListeners.add(listener);
    }

    // ------------------------------------------------------------------------------------------
    // Spawn gate

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        // The raw socket address is the only part of a forwarded login this server can verify.
        // It is captured before the handshake is rewritten so PROXY-mode trust can be limited to
        // the actual proxy. A missing address is treated as untrusted.
        final UUID id = event.getUniqueId();
        if (id != null) {
            final InetAddress raw = event.getRawAddress();
            if (raw == null) {
                rawAddresses.remove(id);
            } else {
                rawAddresses.put(id, raw);
            }
        }

        if (!isActive() || store != null) {
            return;
        }
        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                messages().component("auth-unavailable"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(final AsyncPlayerSpawnLocationEvent event) {
        if (!isActive()) {
            return;
        }
        final UUID id = event.getConnection().getProfile().getId();
        final Location saved = event.getSpawnLocation();
        if (id != null && store != null && saved != null && saved.getWorld() != null) {
            final AuthStore.StoredLocation pending = new AuthStore.StoredLocation(
                    saved.getWorld().getName(),
                    saved.getX(),
                    saved.getY(),
                    saved.getZ(),
                    saved.getYaw(),
                    saved.getPitch());
            runDb(() -> store.savePendingIfAbsent(id, pending));
        }

        // A trusted proxy UUID is the whole proof in PROXY mode when Mojang checks are off, so
        // those players can spawn where they left off. When verification is on, everyone waits in
        // the login area until the name/UUID pair has been confirmed, otherwise a spoofed UUID
        // could reveal the real coordinates before the check finishes. A missing UUID cannot be
        // verified, so it is treated as untrusted and still waits in the login area.
        if (store != null && proxyPremiumBypass(id)) {
            return;
        }

        final Location auth = authLocation(saved);
        if (auth != null) {
            event.setSpawnLocation(auth);
        }
    }

    private boolean proxyPremiumBypass(final UUID id) {
        return id != null
                && config.premiumEnabled()
                && config.premiumMode() == AuthConfig.PremiumMode.PROXY
                && !config.premiumVerifyMojang()
                && id.version() == 4
                && trustedProxy(id);
    }

    /**
     * True when this connection came from an address the operator listed as a proxy.
     *
     * <p>Forwarding headers and handshake fields are never read: a backend that exposes its port
     * would happily accept a forged UUID from anyone. The socket peer is the only trustworthy
     * metadata here.
     */
    private boolean trustedProxy(final UUID id) {
        return proxyTrust.trusts(id == null ? null : rawAddresses.get(id));
    }

    /** A version 4 UUID is the only kind a Mojang account or forwarding proxy can produce. */
    private boolean premiumCandidate(final UUID id) {
        return id != null
                && config.premiumEnabled()
                && config.premiumMode() != AuthConfig.PremiumMode.OFF
                && id.version() == 4;
    }

    // ------------------------------------------------------------------------------------------
    // Join / quit

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID id = player.getUniqueId();

        // The hardcoded server owner skips the login gate entirely.
        if (Owner.isOwner(id)) {
            authenticated.add(id);
            return;
        }
        if (!isActive()) {
            authenticated.add(id);
            return;
        }
        if (store == null) {
            player.kick(messages().component("auth-unavailable"));
            return;
        }

        final long now = System.currentTimeMillis();
        joinTimes.put(id, now);
        lastReminder.put(id, now);

        if (proxyPremiumBypass(id)) {
            messages().send(player, "premium-login");
            completeAuth(player, "premium");
            return;
        }

        if (premiumCandidate(id)) {
            beginWaiting(player);
            requirePassword(player);
            verifyPremium(player, player.getName(), id,
                    config.premiumMode() == AuthConfig.PremiumMode.PROXY && trustedProxy(id));
            return;
        }

        if (!config.sessionEnabled()) {
            beginWaiting(player);
            requirePassword(player);
            return;
        }

        final String address = ip(player);
        final boolean bindIp = config.sessionBindIp();
        query(player, () -> {
            final Optional<AuthStore.Session> session = store.session(id);
            final boolean exists = session.isPresent() && store.exists(id);
            return new SessionCheck(session.orElse(null), exists);
        }, check -> {
            if (!player.isOnline() || authenticated.contains(id)) {
                return;
            }
            final AuthStore.Session session = check.session();
            if (session != null) {
                final boolean ipMatches = !bindIp || session.ip().equals(address);
                if (canRestoreSession(
                                id,
                                true,
                                ipMatches,
                                session.expiresAt(),
                                now)
                        && check.exists()) {
                    completeAuth(player, "session");
                    return;
                }
                runDb(() -> store.clearSession(id));
            }
            beginWaiting(player);
            requirePassword(player);
        });
    }

    private void requirePassword(final Player player) {
        if (store == null) {
            return;
        }
        final UUID id = player.getUniqueId();
        query(player, () -> store.find(id)
                .map(AuthStore.Account::hasPassword)
                .orElse(false), hasPassword -> {
                    if (player.isOnline() && !authenticated.contains(id)) {
                        messages().send(
                                player, hasPassword ? "login-required" : "register-required");
                    }
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        // Blindness is a normal potion effect and is written to player data. Leaving an
        // infinite effect behind would blind the player on a later login-free join.
        if (!authenticated.contains(id)) {
            event.getPlayer().removePotionEffect(PotionEffectType.BLINDNESS);
        }
        authenticated.remove(id);
        prompts.remove(id);
        joinTimes.remove(id);
        lastReminder.remove(id);
        lastNotice.remove(id);
        warnedAboutCommandPassword.remove(id);
        internalTeleports.remove(id);
        premiumCache.remove(id);
        rawAddresses.remove(id);
    }

    private void startTicker() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        if (!isActive()) {
            return;
        }

        final long now = System.currentTimeMillis();
        final long timeoutMillis = config.loginTimeoutSeconds() * 1_000L;
        final long reminderMillis = config.reminderSeconds() * 1_000L;

        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (!isWaiting(player)) {
                continue;
            }
            final UUID id = player.getUniqueId();
            final Long joined = joinTimes.get(id);
            if (timeoutMillis > 0 && joined != null && now - joined >= timeoutMillis) {
                player.kick(messages().component("timeout"));
                continue;
            }

            final Long last = lastReminder.get(id);
            if (last == null || now - last >= reminderMillis) {
                lastReminder.put(id, now);
                player.sendActionBar(messages().component("login-required"));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Commands

    public void requestLogin(final Player player) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        final UUID id = player.getUniqueId();
        if (authenticated.contains(id)) {
            messages().send(player, "already-authenticated");
            return;
        }

        query(player, () -> store.find(id).orElse(null), account -> {
            if (!player.isOnline()) {
                return;
            }
            if (authenticated.contains(id)) {
                messages().send(player, "already-authenticated");
                return;
            }
            if (account == null || !account.hasPassword()) {
                messages().send(player, "not-registered");
                return;
            }
            prompts.put(id, new Prompt(PromptKind.LOGIN, null));
            messages().send(player, "login-prompt");
        });
    }

    /** Command-line login: show the "this ends up in the log" hint once per join. */
    public void loginFromCommand(final Player player, final String password) {
        if (warnedAboutCommandPassword.add(player.getUniqueId())) {
            messages().send(player, "password-command-warning");
        }
        login(player, password);
    }

    public void login(final Player player, final String password) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        final UUID id = player.getUniqueId();
        if (authenticated.contains(id)) {
            messages().send(player, "already-authenticated");
            return;
        }

        final String address = ip(player);
        final long now = System.currentTimeMillis();
        final long cooldown = config.attemptCooldownMillis();
        query(player, () -> {
            final AuthStore.Account account = store.find(id).orElse(null);
            if (account == null || !account.hasPassword()) {
                return new LoginStart(null, false, 0L);
            }
            final boolean allowed = store.acquireAttempt(id, address, now, cooldown);
            return new LoginStart(account, allowed, account.lockedUntil());
        }, start -> {
            if (!player.isOnline()) {
                return;
            }
            if (authenticated.contains(id)) {
                messages().send(player, "already-authenticated");
                return;
            }
            final AuthStore.Account account = start.account();
            if (account == null) {
                messages().send(player, "not-registered");
                return;
            }
            if (!start.allowed()) {
                messages().send(player, "login-cooldown");
                return;
            }

            prompts.remove(id);
            runAsync(player, () -> {
                final boolean ok = PasswordHasher.verify(password, account.passwordHash());
                final String upgraded = ok && PasswordHasher.needsRehash(account.passwordHash())
                        ? PasswordHasher.hash(password)
                        : null;
                sync(() -> finishLogin(
                        player, ok, upgraded, account.passwordHash(), start.lockedUntil()));
            });
        });
    }

    private void finishLogin(
            final Player player,
            final boolean ok,
            final String upgradedHash,
            final String verifiedHash,
            final long lockedUntil) {
        if (!player.isOnline()) {
            return;
        }
        final UUID id = player.getUniqueId();
        final String name = player.getName();
        final String address = ip(player);
        if (ok) {
            // The password may have been changed by another connection or an admin while the
            // hash check ran off-thread. Never authenticate with a stale password, and never
            // let the legacy-hash upgrade overwrite the newer password.
            query(player, () -> {
                final AuthStore.Account current = store.find(id).orElse(null);
                if (current == null || !current.passwordHash().equals(verifiedHash)) {
                    return LoginResult.CHANGED;
                }
                if (upgradedHash != null
                        && !store.updatePasswordIfUnchanged(id, verifiedHash, upgradedHash)) {
                    return LoginResult.CHANGED;
                }
                store.recordLogin(id, name, address, System.currentTimeMillis());
                saveSession(id, address);
                return LoginResult.OK;
            }, result -> {
                if (!player.isOnline()) {
                    return;
                }
                if (result == LoginResult.CHANGED) {
                    messages().send(player, "login-changed");
                    return;
                }
                completeAuth(player, "login");
            });
            return;
        }

        final long now = System.currentTimeMillis();
        if (lockedUntil > now) {
            // A correct password is still accepted above; only wrong guesses stay locked. This
            // prevents someone from locking an account owner out by submitting bad passwords.
            sendLocked(player, lockedUntil);
            return;
        }
        final int maxAttempts = config.maxFailedAttempts();
        final long lockoutMillis = config.lockoutSeconds() * 1_000L;
        query(player, () -> store.recordFailure(id, maxAttempts, lockoutMillis, now)
                .orElse(null), failure -> {
                    if (!player.isOnline() || authenticated.contains(id)) {
                        return;
                    }
                    if (failure != null && failure.lockedUntil() > now) {
                        sendLocked(player, failure.lockedUntil());
                        return;
                    }
                    final int attemptsLeft = failure == null
                            ? 0
                            : Math.max(0, maxAttempts - failure.attempts());
                    messages().send(
                            player,
                            "login-failed",
                            "attempts",
                            Integer.toString(attemptsLeft));
                });
    }

    public void requestRegister(final Player player) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        final UUID id = player.getUniqueId();
        if (authenticated.contains(id)) {
            messages().send(player, "already-authenticated");
            return;
        }

        query(player, () -> store.find(id).orElse(null), account -> {
            if (!player.isOnline()) {
                return;
            }
            if (authenticated.contains(id)) {
                messages().send(player, "already-authenticated");
                return;
            }
            if (account != null && account.hasPassword()) {
                messages().send(player, "already-registered");
                return;
            }

            prompts.put(id, new Prompt(PromptKind.REGISTER, null));
            messages().send(player, "register-prompt", "length",
                    Integer.toString(config.minPasswordLength()));
        });
    }

    public void register(final Player player, final String first, final String second) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        final UUID id = player.getUniqueId();
        if (authenticated.contains(id)) {
            messages().send(player, "already-authenticated");
            return;
        }

        final String address = ip(player);
        final long now = System.currentTimeMillis();
        final long cooldown = config.registerCooldownMillis();
        query(player, () -> {
            final AuthStore.Account account = store.find(id).orElse(null);
            if (account != null && account.hasPassword()) {
                return new RegisterStart(account, false, true);
            }
            // Registration costs the same PBKDF2 work as a login. Throttle it separately so a
            // flood cannot turn into a hash-queue backlog, without touching the login scopes.
            final boolean allowed = store.acquireRegisterAttempt(id, address, now, cooldown);
            return new RegisterStart(account, allowed, false);
        }, start -> {
            if (!player.isOnline()) {
                return;
            }
            if (authenticated.contains(id)) {
                messages().send(player, "already-authenticated");
                return;
            }
            if (start.alreadyRegistered()) {
                messages().send(player, "already-registered");
                return;
            }
            if (!start.allowed()) {
                messages().send(player, "register-cooldown");
                return;
            }
            if (!first.equals(second)) {
                prompts.remove(id);
                messages().send(player, "register-mismatch");
                return;
            }
            if (!validPassword(player, first)) {
                return;
            }

            prompts.remove(id);
            final boolean accountExists = start.account() != null;
            final String name = player.getName();
            runAsync(player, () -> {
                final String hash = PasswordHasher.hash(first);
                sync(() -> persistRegistration(
                        player, id, name, address, hash, accountExists));
            });
        });
    }

    private void persistRegistration(
            final Player player,
            final UUID id,
            final String name,
            final String address,
            final String hash,
            final boolean accountExists) {
        query(player, () -> {
            final long now = System.currentTimeMillis();
            final boolean saved;
            if (accountExists) {
                saved = store.updatePassword(id, hash);
            } else {
                saved = store.create(id, name, hash, address, now);
            }
            if (!saved) {
                return store.exists(id)
                        ? RegistrationResult.ALREADY_REGISTERED
                        : RegistrationResult.FAILED;
            }
            store.recordLogin(id, name, address, now);
            saveSession(id, address);
            cachedAccounts = store.countAccounts();
            return RegistrationResult.SAVED;
        }, result -> {
            if (!player.isOnline()) {
                return;
            }
            switch (result) {
                case SAVED -> completeAuth(player, "register");
                case ALREADY_REGISTERED -> messages().send(player, "already-registered");
                case FAILED -> messages().send(player, "auth-save-failed");
            }
        });
    }

    public void logout(final Player player) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        final UUID id = player.getUniqueId();
        if (!authenticated.contains(id)) {
            messages().send(player, "not-logged-in");
            return;
        }

        runDb(() -> store.clearSession(id));
        premiumCache.remove(id);
        authenticated.remove(id);
        prompts.remove(id);
        rememberLocation(player);
        beginWaiting(player);
        deauthListeners.forEach(listener -> listener.accept(player));
        teleportTo(player, authLocation(player.getLocation()));
        messages().send(player, "logout-success");
    }

    public void requestChangePassword(final Player player) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        final UUID id = player.getUniqueId();
        if (!authenticated.contains(id)) {
            messages().send(player, "login-required");
            return;
        }

        query(player, () -> store.find(id).orElse(null), account -> {
            if (!player.isOnline()) {
                return;
            }
            if (!authenticated.contains(id)) {
                messages().send(player, "login-required");
                return;
            }
            if (account == null || !account.hasPassword()) {
                prompts.put(id, new Prompt(PromptKind.CHANGE_NEW, null));
                messages().send(player, "password-new");
                return;
            }
            prompts.put(id, new Prompt(PromptKind.CHANGE_CURRENT, null));
            messages().send(player, "password-current");
        });
    }

    public void changePassword(
            final Player player, final String current, final String updated) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        final UUID id = player.getUniqueId();
        if (!authenticated.contains(id)) {
            messages().send(player, "login-required");
            return;
        }
        if (!validPassword(player, updated)) {
            return;
        }

        query(player, () -> store.find(id).orElse(null), account -> {
            if (!player.isOnline()) {
                return;
            }
            if (!authenticated.contains(id)) {
                messages().send(player, "login-required");
                return;
            }
            if (account == null || !account.hasPassword()) {
                setPassword(player, updated);
                return;
            }

            runAsync(player, () -> {
                final boolean ok = PasswordHasher.verify(current, account.passwordHash());
                sync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!ok) {
                        messages().send(player, "password-wrong");
                        return;
                    }
                    setPassword(player, updated);
                });
            });
        });
    }

    private void setPassword(final Player player, final String updated) {
        final UUID id = player.getUniqueId();
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        if (!authenticated.contains(id)) {
            prompts.remove(id);
            messages().send(player, "login-required");
            return;
        }
        prompts.remove(id);
        final String name = player.getName();
        final String address = ip(player);
        runAsync(player, () -> {
            final String hash = PasswordHasher.hash(updated);
            sync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!authenticated.contains(id)) {
                    messages().send(player, "login-required");
                    return;
                }
                query(player, () -> {
                    final boolean exists = store.exists(id);
                    final boolean saved;
                    if (exists) {
                        saved = store.updatePassword(id, hash);
                        if (saved) {
                            store.clearFailures(id);
                        }
                    } else {
                        saved = store.create(
                                id, name, hash, address, System.currentTimeMillis());
                    }
                    if (saved) {
                        saveSession(id, address);
                        cachedAccounts = store.countAccounts();
                    }
                    return saved;
                }, saved -> {
                    if (!player.isOnline() || !authenticated.contains(id)) {
                        return;
                    }
                    if (!saved) {
                        messages().send(player, "auth-save-failed");
                        return;
                    }
                    messages().send(player, "password-changed");
                });
            });
        });
    }

    public void premiumStatus(final Player player) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        if (!config.premiumEnabled()) {
            messages().send(player, "premium-disabled");
            return;
        }

        final UUID id = player.getUniqueId();
        final boolean verified = authenticated.contains(id) && premiumCache.containsKey(id);
        final String status;
        if (id.version() != 4) {
            status = "offline-mode UUID - password required";
        } else if (verified) {
            status = "verified";
        } else if (config.premiumMode() == AuthConfig.PremiumMode.PROXY
                && trustedProxy(id)) {
            status = "premium UUID accepted by the trusted proxy; use /premium check to confirm";
        } else if (config.premiumMode() == AuthConfig.PremiumMode.PROXY) {
            status = "connection is not from the trusted proxy; use /premium check or /login";
        } else {
            status = "not checked yet; use /premium check";
        }
        messages().send(player, "premium-status", "status", status);
    }

    public void checkPremium(final Player player) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        if (!config.premiumEnabled() || config.premiumMode() == AuthConfig.PremiumMode.OFF) {
            messages().send(player, "premium-disabled");
            return;
        }

        final UUID id = player.getUniqueId();
        if (id.version() != 4) {
            messages().send(player, "premium-offline");
            return;
        }

        if (config.premiumMode() == AuthConfig.PremiumMode.PROXY
                && !config.premiumVerifyMojang()) {
            if (trustedProxy(id)) {
                premiumCache.put(id, System.currentTimeMillis() + PREMIUM_CACHE_MILLIS);
                runDb(() -> store.setPremium(id, true, System.currentTimeMillis()));
                messages().send(player, "premium-verified");
                if (!authenticated.contains(id)) {
                    completeAuth(player, "premium");
                }
                return;
            }
            // The connection did not come from the configured proxy, so its UUID proves
            // nothing. Fall back to Mojang verification instead of refusing outright; a real
            // premium account still succeeds, a forged one does not.
            warn.accept("Refusing the PROXY premium bypass for " + player.getName()
                    + ": the connection did not come from auth.premium.trusted-proxies.");
        }

        verifyPremium(player, player.getName(), id, false);
    }

    /** Hides or restores the "use /premium" tip, linked from its own clickable opt-out. */
    public void setPremiumPrompt(final Player player, final boolean off) {
        if (!isActive()) {
            messages().send(player, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(player, "auth-unavailable");
            return;
        }
        if (!config.premiumEnabled()) {
            messages().send(player, "premium-disabled");
            return;
        }
        final UUID id = player.getUniqueId();
        runDb(() -> store.setPremiumPromptOff(id, off));
        messages().send(player, off ? "premium-suggestion-muted" : "premium-suggestion-enabled");
    }

    // ------------------------------------------------------------------------------------------
    // Chat / command input

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(final AsyncChatEvent event) {
        if (!isActive()) {
            return;
        }
        final Player player = event.getPlayer();
        final UUID id = player.getUniqueId();

        final Prompt prompt = prompts.get(id);
        if (prompt != null) {
            event.setCancelled(true);
            final String text = PlainTextComponentSerializer.plainText()
                    .serialize(event.message())
                    .trim();
            handlePromptInput(player, prompt, text);
            return;
        }

        if (isWaiting(player) && config.blockChat()) {
            event.setCancelled(true);
            notice(player, "chat-blocked");
        }
    }

    private void handlePromptInput(final Player player, final Prompt prompt, final String text) {
        if (text.isBlank() || text.equalsIgnoreCase("cancel")) {
            prompts.remove(player.getUniqueId());
            messages().send(player, "cancelled");
            return;
        }

        if (prompt.kind() == PromptKind.CHANGE_CURRENT
                || prompt.kind() == PromptKind.CHANGE_NEW
                || prompt.kind() == PromptKind.CHANGE_CONFIRM) {
            if (!authenticated.contains(player.getUniqueId())) {
                prompts.remove(player.getUniqueId());
                messages().send(player, "login-required");
                return;
            }
        }

        switch (prompt.kind()) {
            case LOGIN -> {
                prompts.remove(player.getUniqueId());
                login(player, text);
            }
            case REGISTER -> {
                if (!validPassword(player, text)) {
                    return;
                }
                prompts.put(player.getUniqueId(),
                        new Prompt(PromptKind.REGISTER_CONFIRM, PromptDigest.of(text)));
                messages().send(player, "register-confirm");
            }
            case REGISTER_CONFIRM -> {
                prompts.remove(player.getUniqueId());
                if (!PromptDigest.matches(text, prompt.first())) {
                    messages().send(player, "register-mismatch");
                    return;
                }
                register(player, text, text);
            }
            case CHANGE_CURRENT -> {
                prompts.remove(player.getUniqueId());
                verifyCurrentThenPromptNew(player, text);
            }
            case CHANGE_NEW -> {
                if (!validPassword(player, text)) {
                    return;
                }
                prompts.put(player.getUniqueId(),
                        new Prompt(PromptKind.CHANGE_CONFIRM, PromptDigest.of(text)));
                messages().send(player, "register-confirm");
            }
            case CHANGE_CONFIRM -> {
                prompts.remove(player.getUniqueId());
                if (!PromptDigest.matches(text, prompt.first())) {
                    messages().send(player, "register-mismatch");
                    return;
                }
                if (!validPassword(player, text)) {
                    return;
                }
                setPassword(player, text);
            }
        }
    }

    private void verifyCurrentThenPromptNew(final Player player, final String current) {
        final UUID id = player.getUniqueId();
        if (!authenticated.contains(id)) {
            prompts.remove(id);
            messages().send(player, "login-required");
            return;
        }
        query(player, () -> store.find(id).orElse(null), account -> {
            if (!player.isOnline()) {
                return;
            }
            if (!authenticated.contains(id)) {
                prompts.remove(id);
                messages().send(player, "login-required");
                return;
            }
            if (account == null || !account.hasPassword()) {
                prompts.put(id, new Prompt(PromptKind.CHANGE_NEW, null));
                messages().send(player, "password-new");
                return;
            }

            runAsync(player, () -> {
                final boolean ok = PasswordHasher.verify(current, account.passwordHash());
                sync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!ok) {
                        messages().send(player, "password-wrong");
                        return;
                    }
                    if (!authenticated.contains(id)) {
                        messages().send(player, "login-required");
                        return;
                    }
                    prompts.put(id, new Prompt(PromptKind.CHANGE_NEW, null));
                    messages().send(player, "password-new");
                });
            });
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (!isActive() || !config.blockCommands() || !isWaiting(event.getPlayer())) {
            return;
        }

        final String label = commandLabel(event.getMessage());
        if (ALLOWED_COMMANDS.contains(label)) {
            return;
        }
        event.setCancelled(true);
        notice(event.getPlayer(), "command-blocked");
    }

    @EventHandler
    public void onCommandSuggestions(final PlayerCommandSendEvent event) {
        if (isActive() && isWaiting(event.getPlayer())) {
            event.getCommands().retainAll(ALLOWED_COMMANDS);
        }
    }

    /**
     * Wraps a command executor so a waiting player cannot reach it through another plugin's
     * {@code dispatchCommand}, which does not have to fire {@link PlayerCommandPreprocessEvent}.
     */
    public CommandExecutor commandGuard(final CommandExecutor delegate) {
        return (sender, command, label, args) -> {
            final String name = command == null ? label : command.getName();
            if (blocksCommand(sender, name)) {
                return true;
            }
            return delegate.onCommand(sender, command, label, args);
        };
    }

    private boolean blocksCommand(final CommandSender sender, final String name) {
        if (!isActive() || !config.blockCommands() || !(sender instanceof final Player player)) {
            return false;
        }
        if (!isWaiting(player)) {
            return false;
        }
        final String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (ALLOWED_COMMANDS.contains(normalized)) {
            return false;
        }
        notice(player, "command-blocked");
        return true;
    }

    private static String commandLabel(final String message) {
        if (message == null || !message.startsWith("/")) {
            return "";
        }
        int end = message.indexOf(' ');
        if (end < 0) {
            end = message.length();
        }
        String label = message.substring(1, end).toLowerCase(Locale.ROOT);
        final int namespace = label.indexOf(':');
        if (namespace >= 0) {
            label = label.substring(namespace + 1);
        }
        return label;
    }

    // ------------------------------------------------------------------------------------------
    // Waiting-room protections

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (!isWaiting(event.getPlayer())) {
            return;
        }
        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (to == null
                || (from.getX() == to.getX()
                        && from.getY() == to.getY()
                        && from.getZ() == to.getZ())) {
            return;
        }

        final Location stay = from.clone();
        stay.setYaw(to.getYaw());
        stay.setPitch(to.getPitch());
        event.setTo(stay);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (isWaiting(event.getPlayer()) && !internalTeleports.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof final Player player && isWaiting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageByPlayer(final EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof final Player player && isWaiting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFood(final FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof final Player player && isWaiting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (isWaiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (isWaiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAtEntity(final PlayerInteractAtEntityEvent event) {
        if (isWaiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        if (isWaiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        if (isWaiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        if (isWaiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(final PlayerItemConsumeEvent event) {
        if (isWaiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
        if (isWaiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof final Player player && isWaiting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(final InventoryOpenEvent event) {
        if (event.getPlayer() instanceof final Player player && isWaiting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof final Player player && isWaiting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof final Player player && isWaiting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        if (isWaiting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(final PlayerRespawnEvent event) {
        if (!isWaiting(event.getPlayer())) {
            return;
        }
        final Location auth = authLocation(event.getRespawnLocation());
        if (auth != null) {
            event.setRespawnLocation(auth);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Auth completion

    private void completeAuth(final Player player, final String reason) {
        if (!player.isOnline()) {
            return;
        }
        final UUID id = player.getUniqueId();
        if (!authenticated.add(id)) {
            return;
        }

        prompts.remove(id);
        joinTimes.remove(id);
        lastReminder.remove(id);
        lastNotice.remove(id);

        player.setInvulnerable(false);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.setFallDistance(0);
        // The command list was cut down to /login, /register and /premium when the player joined.
        // The client keeps that list until the server sends a new one.
        player.updateCommands();

        for (final Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            if (isWaiting(other)) {
                player.hidePlayer(plugin, other);
                other.hidePlayer(plugin, player);
            } else {
                player.showPlayer(plugin, other);
                other.showPlayer(plugin, player);
            }
        }

        // Only the pending row that was actually restored may be removed. If its world is not
        // loaded right now, keep the row so the player still returns there after the next join.
        restorePending(player);

        switch (reason) {
            case "session" -> messages().send(player, "session-restored");
            case "login" -> messages().send(player, "login-success");
            case "register" -> messages().send(player, "register-success");
            default -> {
                // Premium logins already received their own message.
            }
        }

        // Premium players never need the tip, and players who clicked the opt-out should not see
        // it again. Everyone else gets it on every successful login until they opt out.
        if (!"premium".equals(reason)) {
            sendPremiumSuggestion(player);
        }
        notifyAuthenticated(player);
    }

    /** Loads the saved pre-login location without reading SQLite on the server thread. */
    private void restorePending(final Player player) {
        final UUID id = player.getUniqueId();
        query(null, () -> store.peekPending(id), pending -> {
            if (!player.isOnline() || !authenticated.contains(id)) {
                return;
            }
            final Location target = pending.map(this::toLocation).orElse(null);
            if (target != null) {
                runDb(() -> store.deletePending(id));
                teleportTo(player, target);
            } else {
                // While waiting, every move packet was answered with a "go back" correction, so
                // the client's idea of its position can drift from the server's. A teleport to
                // the current spot resynchronises the client.
                teleportTo(player, player.getLocation());
            }
        });
    }

    private void beginWaiting(final Player player) {
        final UUID id = player.getUniqueId();
        if (Owner.isOwner(id)) {
            authenticated.add(id);
            return;
        }
        authenticated.remove(id);
        player.setInvulnerable(true);
        player.updateCommands();
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setVelocity(new Vector(0, 0, 0));

        if (config.blindness()) {
            // Removing first makes the single-argument add (which respects an existing, stronger
            // effect) behave like the deprecated force variant.
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS,
                    PotionEffect.INFINITE_DURATION,
                    0,
                    false,
                    false,
                    false));
        }
        if (config.hidePlayers()) {
            for (final Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(player)) {
                    continue;
                }
                player.hidePlayer(plugin, other);
                other.hidePlayer(plugin, player);
            }
        }
    }

    private void notifyAuthenticated(final Player player) {
        for (final Consumer<Player> listener : authListeners) {
            listener.accept(player);
        }
    }

    private void teleportTo(final Player player, final Location target) {
        if (target == null || !player.isOnline()) {
            return;
        }
        final UUID id = player.getUniqueId();
        internalTeleports.add(id);
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (player.isOnline()) {
                    player.teleport(target);
                    player.setVelocity(new Vector(0, 0, 0));
                }
            } finally {
                internalTeleports.remove(id);
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Premium checks

    private void verifyPremium(
            final Player player,
            final String name,
            final UUID id,
            final boolean trustProxyOnUnknown) {
        final Long cached = premiumCache.get(id);
        if (cached != null && cached > System.currentTimeMillis()) {
            messages().send(player, "premium-verified");
            if (!authenticated.contains(id)) {
                completeAuth(player, "premium");
            }
            return;
        }

        final CompletableFuture<PremiumVerifier.Result> verification;
        try {
            verification = premiumVerifier.verify(name, id);
        } catch (final RuntimeException exception) {
            warn.accept("Could not start the Mojang check: " + exception.getMessage());
            handlePremiumResult(player, PremiumVerifier.Result.UNKNOWN, trustProxyOnUnknown);
            return;
        }
        verification.whenComplete((result, error) -> {
            final PremiumVerifier.Result outcome =
                    error == null && result != null ? result : PremiumVerifier.Result.UNKNOWN;
            sync(() -> handlePremiumResult(player, outcome, trustProxyOnUnknown));
        });
    }

    private void handlePremiumResult(
            final Player player,
            final PremiumVerifier.Result result,
            final boolean trustProxyOnUnknown) {
        if (!player.isOnline()) {
            return;
        }
        final UUID id = player.getUniqueId();

        switch (result) {
            case VERIFIED -> {
                premiumCache.put(id, System.currentTimeMillis() + PREMIUM_CACHE_MILLIS);
                runDb(() -> store.setPremium(id, true, System.currentTimeMillis()));
                messages().send(player, "premium-verified");
                if (!authenticated.contains(id)) {
                    completeAuth(player, "premium");
                }
            }
            case MISMATCH -> {
                premiumCache.remove(id);
                if (authenticated.contains(id)) {
                    messages().send(player, "premium-unavailable");
                    return;
                }
                plugin.getLogger().warning(
                        "A premium UUID was presented for " + player.getName()
                                + ", but Mojang says the name and UUID do not match.");
                messages().send(player, "premium-unavailable");
                requirePassword(player);
            }
            case UNKNOWN -> {
                if (trustProxyOnUnknown && !authenticated.contains(id)) {
                    // The proxy remains the authority in PROXY mode; a Mojang outage must not
                    // lock everyone out. MOJANG mode deliberately fails closed instead.
                    plugin.getLogger().warning(
                            "Could not verify " + player.getName()
                                    + " with Mojang; trusting the proxy UUID for this join.");
                    completeAuth(player, "premium");
                    return;
                }
                messages().send(player, "premium-unavailable");
                if (!authenticated.contains(id)) {
                    requirePassword(player);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Admin surface

    public List<String> statusLines() {
        final List<String> lines = new ArrayList<>();
        lines.add("Auth: " + (!available
                ? "unavailable (database failed to open)"
                : config.enabled() ? "enabled" : "disabled in config"));
        if (store != null) {
            lines.add("Database: " + store.file().toAbsolutePath()
                    + " (" + (cachedAccounts < 0
                            ? "account count pending"
                            : cachedAccounts + " accounts") + ")");
        }
        lines.add("Legacy accounts: imported only from an operator-supplied database");
        final Path legacy = simpleLoginPath();
        lines.add("SimpleLogin database: " + legacy.toAbsolutePath()
                + (Files.isRegularFile(legacy) ? " (found)" : " (not found)"));
        lines.add("Premium mode: " + config.premiumMode()
                + " (verify with Mojang: " + config.premiumVerifyMojang() + ")");
        lines.add("Trusted proxies: " + (proxyTrust.isEmpty()
                ? "none - the passwordless proxy bypass is disabled"
                : config.premiumTrustedProxies().size() + " configured"));
        lines.add("Sessions: " + (config.sessionEnabled()
                ? config.sessionDurationMillis() / 3_600_000L + "h, bind IP: "
                        + config.sessionBindIp()
                : "disabled"));
        return lines;
    }

    /**
     * Imports the operator-supplied SimpleLogin database on the database worker.
     *
     * <p>The callback receives the number of copied accounts, or {@code -1} when the auth
     * database is unavailable or the import failed.
     */
    public void importLegacyNow(
            final CommandSender sender, final Consumer<Integer> callback) {
        if (store == null) {
            callback.accept(-1);
            return;
        }
        runDb(sender, () -> {
            final int imported = importAllLegacy(System.currentTimeMillis());
            if (imported >= 0) {
                cachedAccounts = store.countAccounts();
            }
            sync(() -> callback.accept(imported));
        });
    }

    /**
     * {@code /changepass <player> <new password>} for an operator.
     *
     * <p>The target does not have to be online. Their name is looked up in the account table, and
     * on an offline-mode server the deterministic offline UUID is used as a fallback, which also
     * covers imported rows that never logged in after the migration.
     */
    public void adminChangePassword(
            final CommandSender sender, final String targetName, final String password) {
        if (!isActive()) {
            messages().send(sender, "auth-disabled");
            return;
        }
        if (store == null) {
            messages().send(sender, "auth-unavailable");
            return;
        }
        if (password.length() < config.minPasswordLength()) {
            messages().send(sender, "register-too-short", "length",
                    Integer.toString(config.minPasswordLength()));
            return;
        }
        if (password.length() > config.maxPasswordLength()) {
            messages().send(sender, "password-too-long", "length",
                    Integer.toString(config.maxPasswordLength()));
            return;
        }
        if (password.equalsIgnoreCase(targetName)) {
            messages().send(sender, "password-is-name");
            return;
        }

        final Player online = Bukkit.getPlayerExact(targetName);
        final UUID onlineId = online == null ? null : online.getUniqueId();
        final boolean onlineMode = Bukkit.getOnlineMode();
        query(sender, () -> resolveAccountId(targetName, onlineId, onlineMode), id -> {
            if (id == null) {
                messages().send(sender, "admin-password-not-found", "player", targetName);
                return;
            }

            runAsync(sender, () -> {
                final String hash = PasswordHasher.hash(password);
                sync(() -> persistAdminPassword(sender, id, targetName, hash));
            });
        });
    }

    private void persistAdminPassword(
            final CommandSender sender,
            final UUID id,
            final String targetName,
            final String hash) {
        query(sender, () -> {
            boolean saved = store.setPasswordAndName(id, targetName, hash);
            if (!saved) {
                saved = store.create(
                        id, targetName, hash, "admin", System.currentTimeMillis());
            }
            store.clearSession(id);
            if (!saved) {
                return false;
            }
            store.clearFailures(id);
            cachedAccounts = store.countAccounts();
            return true;
        }, saved -> {
            prompts.remove(id);
            if (!saved) {
                messages().send(sender, "auth-save-failed");
                return;
            }

            messages().send(sender, "admin-password-changed", "player", targetName);
            final Player target = Bukkit.getPlayer(id);
            if (target != null) {
                if (authenticated.remove(id)) {
                    rememberLocation(target);
                    beginWaiting(target);
                    deauthListeners.forEach(listener -> listener.accept(target));
                    teleportTo(target, authLocation(target.getLocation()));
                }
                messages().send(target, "password-changed-by-admin");
            }
        });
    }

    /** Deletes an account; an online player is moved back to the login area. */
    public void unregister(
            final CommandSender sender,
            final String name,
            final Consumer<String> callback) {
        if (store == null) {
            callback.accept("The auth database is unavailable.");
            return;
        }

        final Player online = Bukkit.getPlayerExact(name);
        final UUID onlineId = online == null ? null : online.getUniqueId();
        runDb(sender, () -> {
            final UUID id = onlineId != null
                    ? onlineId
                    : store.findByName(name).map(AuthStore.Account::uuid).orElse(null);
            if (id == null) {
                sync(() -> callback.accept("No account found for " + name + "."));
                return;
            }

            final boolean removed = store.delete(id);
            store.clearSession(id);
            cachedAccounts = store.countAccounts();
            sync(() -> {
                prompts.remove(id);
                premiumCache.remove(id);
                accountDeletedListeners.forEach(listener -> listener.accept(id));
                if (online != null && online.isOnline()) {
                    authenticated.remove(id);
                    rememberLocation(online);
                    beginWaiting(online);
                    deauthListeners.forEach(listener -> listener.accept(online));
                    teleportTo(online, authLocation(online.getLocation()));
                    messages().send(online, "not-registered");
                }
                callback.accept(removed
                        ? "Deleted the account for " + name + "."
                        : "No stored password existed for " + name + ".");
            });
        });
    }

    // ------------------------------------------------------------------------------------------
    // Helpers

    private boolean isWaiting(final Player player) {
        return isActive() && !authenticated.contains(player.getUniqueId());
    }

    /**
     * Shows the one-time-per-login tip unless the player already is premium or opted out.
     *
     * <p>On an offline-mode server every UUID is version 3, so {@code /premium} can never prove
     * ownership; showing the tip there would only send players into a dead end.
     */
    private void sendPremiumSuggestion(final Player player) {
        if (store == null
                || !config.premiumSuggestion()
                || !config.premiumEnabled()
                || config.premiumMode() == AuthConfig.PremiumMode.OFF) {
            return;
        }

        final UUID id = player.getUniqueId();
        if (premiumCache.containsKey(id)) {
            return;
        }
        if (id.version() != 4 && !Bukkit.getOnlineMode()) {
            return;
        }

        query(null, () -> store.find(id).orElse(null), account -> {
            if (!player.isOnline() || !authenticated.contains(id)) {
                return;
            }
            if (account != null && (account.premium() || account.premiumPromptOff())) {
                return;
            }
            messages().send(player, "premium-suggestion");
        });
    }

    /**
     * Online player, stored name, then the deterministic offline UUID.
     *
     * <p>Runs on the database worker: the online lookup and {@link Bukkit#getOnlineMode()} are
     * captured on the server thread by the caller.
     */
    private UUID resolveAccountId(
            final String name, final UUID onlineId, final boolean onlineMode) {
        if (store == null || name == null || name.isBlank()) {
            return null;
        }
        if (onlineId != null) {
            return onlineId;
        }

        final Optional<AuthStore.Account> stored = store.findByName(name);
        if (stored.isPresent()) {
            return stored.get().uuid();
        }

        if (!onlineMode) {
            // The same UUID SimpleLogin used: UUID.nameUUIDFromBytes("OfflinePlayer:" + name).
            final UUID offline = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            if (store.exists(offline)) {
                return offline;
            }
        }
        return null;
    }

    private void sendLocked(final Player player, final long lockedUntil) {
        final long seconds = Math.max(1L, (lockedUntil - System.currentTimeMillis() + 999L) / 1_000L);
        messages().send(player, "login-locked", "seconds", Long.toString(seconds));
    }

    /**
     * A stored session is only proof for a version-4 UUID, which an online-mode server or a
     * forwarding proxy produced. Offline UUIDs are derived from the player name, so accepting a
     * session for one would let anyone using that name skip the password.
     */
    static boolean canRestoreSession(
            final UUID id,
            final boolean enabled,
            final boolean ipMatches,
            final long expiresAt,
            final long now) {
        return enabled
                && id != null
                && id.version() == 4
                && ipMatches
                && expiresAt > now;
    }

    private boolean validPassword(final Player player, final String password) {
        if (password.length() < config.minPasswordLength()) {
            messages().send(player, "register-too-short", "length",
                    Integer.toString(config.minPasswordLength()));
            return false;
        }
        if (password.length() > config.maxPasswordLength()) {
            messages().send(player, "password-too-long", "length",
                    Integer.toString(config.maxPasswordLength()));
            return false;
        }
        if (password.equalsIgnoreCase(player.getName())) {
            messages().send(player, "password-is-name");
            return false;
        }
        return true;
    }

    /** Runs on the database worker: refreshes or clears the stored session for this login. */
    private void saveSession(final UUID id, final String address) {
        if (!config.sessionEnabled() || config.sessionDurationMillis() <= 0) {
            store.clearSession(id);
            return;
        }
        store.saveSession(
                id,
                address,
                System.currentTimeMillis() + config.sessionDurationMillis());
    }

    private void notice(final Player player, final String key) {
        final long now = System.currentTimeMillis();
        final Long previous = lastNotice.get(player.getUniqueId());
        if (previous != null && now - previous < NOTICE_COOLDOWN_MILLIS) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        messages().send(player, key);
    }

    private AuthMessages messages() {
        return config.messages();
    }

    /** Saves where the player is right now, so logging in can return them here. */
    private void rememberLocation(final Player player) {
        final Location location = player.getLocation();
        final World world = location.getWorld();
        if (store == null || world == null) {
            return;
        }
        final UUID id = player.getUniqueId();
        final AuthStore.StoredLocation stored = new AuthStore.StoredLocation(
                world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
        runDb(() -> store.savePending(id, stored));
    }

    private Location authLocation(final Location fallback) {
        World world = null;
        if (!config.world().isBlank()) {
            world = Bukkit.getWorld(config.world());
        }
        if (world == null && fallback != null) {
            world = fallback.getWorld();
        }
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().getFirst();
        }
        if (world == null) {
            return fallback;
        }
        if (config.useWorldSpawn()) {
            return world.getSpawnLocation();
        }
        return new Location(
                world, config.x(), config.y(), config.z(), config.yaw(), config.pitch());
    }

    private Location toLocation(final AuthStore.StoredLocation stored) {
        final World world = Bukkit.getWorld(stored.world());
        if (world == null) {
            return null;
        }
        return new Location(
                world, stored.x(), stored.y(), stored.z(), stored.yaw(), stored.pitch());
    }

    private static String ip(final Player player) {
        final InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return "unknown";
        }
        return address.getAddress().getHostAddress();
    }

    private Path simpleLoginPath() {
        final Path configured = Path.of(config.simpleLoginDatabase());
        return configured.isAbsolute() ? configured : folder.resolve(configured).normalize();
    }

    private void runAsync(final Runnable task) {
        runAsync(null, task);
    }

    private void runAsync(final CommandSender sender, final Runnable task) {
        if (closed) {
            notifyBusy(sender);
            return;
        }
        try {
            executor.execute(task);
        } catch (final RejectedExecutionException exception) {
            warn.accept("Auth task was rejected (queue full or shutting down).");
            notifyBusy(sender);
        }
    }

    /** Queues short SQLite work on the dedicated, bounded database worker. */
    private void runDb(final Runnable task) {
        runDb(null, task);
    }

    private void runDb(final CommandSender sender, final Runnable task) {
        if (closed) {
            notifyBusy(sender);
            return;
        }
        try {
            databaseExecutor.execute(task);
        } catch (final RejectedExecutionException exception) {
            warn.accept("Auth database task was rejected (queue full or shutting down).");
            notifyBusy(sender);
        }
    }

    /**
     * Reads from SQLite on the database worker and hands the result back on the server thread.
     *
     * <p>The callback is skipped when auth has been closed while the query ran; no caller may
     * touch the world or a player from the worker itself.
     */
    private <T> void query(
            final CommandSender sender,
            final Supplier<T> read,
            final Consumer<T> callback) {
        runDb(sender, () -> {
            final T value;
            try {
                value = read.get();
            } catch (final RuntimeException exception) {
                warn.accept("Auth database read failed: " + exception.getMessage());
                return;
            }
            sync(() -> callback.accept(value));
        });
    }

    private void notifyBusy(final CommandSender sender) {
        if (sender == null) {
            return;
        }
        if (sender instanceof final Player player && !player.isOnline()) {
            return;
        }
        messages().send(sender, "auth-busy");
    }

    private void sync(final Runnable task) {
        if (closed || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private enum PromptKind {
        LOGIN,
        REGISTER,
        REGISTER_CONFIRM,
        CHANGE_CURRENT,
        CHANGE_NEW,
        CHANGE_CONFIRM
    }

    private enum LoginResult {
        OK,
        CHANGED
    }

    private enum RegistrationResult {
        SAVED,
        ALREADY_REGISTERED,
        FAILED
    }

    private record Prompt(PromptKind kind, String first) {}

    private record SessionCheck(AuthStore.Session session, boolean exists) {}

    private record LoginStart(AuthStore.Account account, boolean allowed, long lockedUntil) {}

    private record RegisterStart(
            AuthStore.Account account, boolean allowed, boolean alreadyRegistered) {}
}
