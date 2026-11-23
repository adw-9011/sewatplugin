package io.github.near.sweats.currency;

import io.github.near.sweats.runes.RuneDrops;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Balances, mob rewards, the ledger and the currency action bar.
 *
 * <p>Balances are cached in memory. The database is only touched when a balance is first loaded or
 * actually changes; the action bar renders from the cache and never polls SQLite. All database
 * work runs on a single bounded worker, and every callback returns to the server thread before it
 * touches a player or a Bukkit object.
 */
public final class CurrencyService implements Listener, AutoCloseable {

    public static final long DEFAULT_MAX_GIVE = 1_000_000_000L;

    private static final long SHUTDOWN_WAIT_SECONDS = 3L;

    public enum GrantResult {
        SUCCESS,
        DISABLED,
        INVALID_AMOUNT,
        REJECTED
    }

    private final JavaPlugin plugin;
    private final Path folder;
    private final Predicate<UUID> authenticated;
    private final Consumer<String> warn;
    private final ExecutorService database;
    private final Consumer<Runnable> mainThread;
    private final CurrencyStore store;
    private final Map<BalanceKey, Long> balances = new ConcurrentHashMap<>();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled = true;
    private volatile long normalCoinReward = 5L;
    private volatile long bossCoinReward = 100L;
    private volatile long normalShardReward;
    private volatile long bossShardReward;
    private volatile long maxGiveAmount = DEFAULT_MAX_GIVE;
    private volatile int reasonMaxLength = 80;
    private volatile boolean actionBarEnabled = true;
    private volatile long actionBarIntervalTicks = 40L;

    private volatile boolean closed;
    private BukkitTask actionBarTask;

    public CurrencyService(
            final JavaPlugin plugin,
            final Path folder,
            final Predicate<UUID> authenticated,
            final Consumer<String> warn) {
        this(plugin, folder, authenticated, warn, null, null);
    }

    /** Test-friendly construction: no plugin, no scheduler, caller owns the executor. */
    CurrencyService(
            final Path folder,
            final Predicate<UUID> authenticated,
            final Consumer<String> warn,
            final ExecutorService database,
            final Consumer<Runnable> mainThread) {
        this(null, folder, authenticated, warn, database, mainThread);
    }

    private CurrencyService(
            final JavaPlugin plugin,
            final Path folder,
            final Predicate<UUID> authenticated,
            final Consumer<String> warn,
            final ExecutorService database,
            final Consumer<Runnable> mainThread) {
        this.plugin = plugin;
        this.folder = folder;
        this.authenticated = authenticated;
        this.warn = warn;
        this.database = database == null ? databaseExecutor() : database;
        this.mainThread = mainThread != null
                ? mainThread
                : plugin == null
                        ? Runnable::run
                        : task -> Bukkit.getScheduler().runTask(plugin, task);
        this.store = new CurrencyStore(folder.resolve("currency.db"), warn);
    }

    public void open() throws SQLException, java.io.IOException {
        store.open();
        if (plugin != null) {
            reload();
        }
    }

    /** Starts the action-bar display for online authenticated players. */
    public void start() {
        if (plugin == null) {
            return;
        }
        restartActionBar();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (authenticated.test(player.getUniqueId())) {
                load(player.getUniqueId());
            }
        }
    }

    private void restartActionBar() {
        if (actionBarTask != null) {
            actionBarTask.cancel();
            actionBarTask = null;
        }
        if (plugin == null || closed || !actionBarEnabled) {
            return;
        }
        actionBarTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::refreshActionBars, actionBarIntervalTicks, actionBarIntervalTicks);
    }

    private void refreshActionBars() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final UUID id = player.getUniqueId();
            if (loaded.contains(id) && authenticated.test(id)) {
                player.sendActionBar(render(id));
            }
        }
    }

    /** Re-reads the currency section of config.yml. */
    public void reload() {
        if (plugin == null) {
            return;
        }
        final FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("currency.enabled", true);
        normalCoinReward = positive(config.getLong("currency.rewards.sweat-coins.normal-mob", 5L));
        bossCoinReward = positive(config.getLong("currency.rewards.sweat-coins.boss-mob", 100L));
        normalShardReward = positive(config.getLong("currency.rewards.tryhardshards.normal-mob", 0L));
        bossShardReward = positive(config.getLong("currency.rewards.tryhardshards.boss-mob", 0L));
        maxGiveAmount = Math.max(1L, config.getLong("currency.max-give-amount", DEFAULT_MAX_GIVE));
        reasonMaxLength = Math.max(1, Math.min(200, config.getInt("currency.reason-max-length", 80)));
        actionBarEnabled = config.getBoolean("currency.action-bar.enabled", true);
        actionBarIntervalTicks =
                Math.max(20L, config.getLong("currency.action-bar.interval-ticks", 40L));
        restartActionBar();
    }

    public boolean enabled() {
        return enabled;
    }

    public long maxGiveAmount() {
        return maxGiveAmount;
    }

    /** The cached balance. A balance that has not been loaded yet reports zero. */
    public long balance(final UUID id, final CurrencyType currency) {
        return balances.getOrDefault(new BalanceKey(id, currency), 0L);
    }

    public boolean loaded(final UUID id) {
        return loaded.contains(id);
    }

    /** Loads both balances off the server thread and refreshes the action bar when done. */
    public void load(final UUID id) {
        if (closed) {
            return;
        }
        try {
            database.execute(() -> {
                final Map<CurrencyType, Long> values = store.balances(id);
                onMain(() -> {
                    for (final Map.Entry<CurrencyType, Long> entry : values.entrySet()) {
                        balances.put(new BalanceKey(id, entry.getKey()), entry.getValue());
                    }
                    loaded.add(id);
                    showIfOnline(id);
                });
            });
        } catch (final RejectedExecutionException rejected) {
            warn.accept("Currency load queue is full; balance for " + id + " will retry later");
        }
    }

    /**
     * Grants currency. Validation happens on the calling thread; the database write and the
     * callback run asynchronously, with the callback delivered on the server thread.
     */
    public void grant(
            final UUID issuer,
            final UUID target,
            final CurrencyType currency,
            final long amount,
            final String reason,
            final String source,
            final Consumer<GrantResult> callback) {
        final Consumer<GrantResult> safeCallback = callback == null ? result -> {} : callback;
        if (!enabled) {
            onMain(() -> safeCallback.accept(GrantResult.DISABLED));
            return;
        }
        if (amount <= 0 || amount > maxGiveAmount) {
            onMain(() -> safeCallback.accept(GrantResult.INVALID_AMOUNT));
            return;
        }
        final String safeReason = sanitizeReason(reason);
        final String safeSource = source == null || source.isBlank() ? "unspecified" : source;
        if (closed) {
            onMain(() -> safeCallback.accept(GrantResult.REJECTED));
            return;
        }
        try {
            database.execute(() -> {
                final OptionalLong updated = store.add(
                        target, currency, amount, issuer, safeReason, safeSource,
                        System.currentTimeMillis());
                if (updated.isEmpty()) {
                    onMain(() -> safeCallback.accept(GrantResult.REJECTED));
                    return;
                }
                balances.put(new BalanceKey(target, currency), updated.getAsLong());
                loaded.add(target);
                onMain(() -> {
                    safeCallback.accept(GrantResult.SUCCESS);
                    showIfOnline(target);
                });
            });
        } catch (final RejectedExecutionException rejected) {
            onMain(() -> safeCallback.accept(GrantResult.REJECTED));
        }
    }

    public String sanitizeReason(final String reason) {
        if (reason == null) {
            return "unspecified";
        }
        final StringBuilder cleaned = new StringBuilder(reason.length());
        boolean space = false;
        for (int index = 0; index < reason.length(); index++) {
            final char character = reason.charAt(index);
            if (Character.isISOControl(character)) {
                continue;
            }
            if (Character.isWhitespace(character)) {
                if (!space && cleaned.length() > 0) {
                    cleaned.append(' ');
                    space = true;
                }
                continue;
            }
            space = false;
            cleaned.append(character);
            if (cleaned.length() >= reasonMaxLength) {
                break;
            }
        }
        final String result = cleaned.toString().trim();
        return result.isEmpty() ? "unspecified" : result;
    }

    // ------------------------------------------------------------------ listeners

    /** Called after a successful login so the player sees their real balances. */
    public void onAuthenticated(final Player player) {
        load(player.getUniqueId());
    }

    public void onDeauthenticated(final Player player) {
        loaded.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        loaded.remove(id);
        balances.keySet().removeIf(key -> key.player().equals(id));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(final EntityDeathEvent event) {
        if (!enabled) {
            return;
        }
        final LivingEntity entity = event.getEntity();
        if (entity instanceof Player
                || entity instanceof Villager
                || entity instanceof WanderingTrader
                || entity instanceof Tameable tameable && tameable.isTamed()
                || entity.hasMetadata("NPC")) {
            return;
        }
        // Ender dragons are not Mob instances; every other combat mob is.
        if (!(entity instanceof Mob) && !(entity instanceof EnderDragon)) {
            return;
        }
        final Player killer = entity.getKiller();
        if (killer == null || !authenticated.test(killer.getUniqueId())) {
            return;
        }

        final boolean boss = RuneDrops.isBoss(entity.getType());
        final long coins = boss ? bossCoinReward : normalCoinReward;
        final long shards = boss ? bossShardReward : normalShardReward;
        final String reason = boss ? "boss-kill" : "mob-kill";
        final String source = "mob:" + entity.getType().name();
        if (coins > 0) {
            grant(null, killer.getUniqueId(), CurrencyType.SWEAT_COINS, coins, reason, source, null);
        }
        if (shards > 0) {
            grant(null, killer.getUniqueId(), CurrencyType.TRYHARD_SHARDS, shards, reason, source, null);
        }
    }

    // ------------------------------------------------------------------ action bar

    private void showIfOnline(final UUID id) {
        if (plugin == null || !actionBarEnabled) {
            return;
        }
        final Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline() && loaded.contains(id)) {
            player.sendActionBar(render(id));
        }
    }

    /**
     * Renders the action bar with both currencies.
     * Format: {@code Sweat coins: <balance>    TryHard Shards: <balance>}
     * Sweat Coins label and value: yellow.
     * TryHard Shards label and value: purple.
     * Four spaces between the two currencies for readability.
     */
    private Component render(final UUID id) {
        return Component.text("Sweat coins: ", CurrencyType.SWEAT_COINS.color())
                .append(Component.text(balance(id, CurrencyType.SWEAT_COINS),
                        CurrencyType.SWEAT_COINS.color()))
                .append(Component.text("    ", NamedTextColor.DARK_GRAY))
                .append(Component.text("TryHard Shards: ", CurrencyType.TRYHARD_SHARDS.color()))
                .append(Component.text(balance(id, CurrencyType.TRYHARD_SHARDS),
                        CurrencyType.TRYHARD_SHARDS.color()));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (actionBarTask != null) {
            actionBarTask.cancel();
            actionBarTask = null;
        }
        database.shutdown();
        try {
            if (!database.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                database.shutdownNow();
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            database.shutdownNow();
        }
        store.close();
        balances.clear();
        loaded.clear();
    }

    private void onMain(final Runnable task) {
        mainThread.accept(task);
    }

    private static long positive(final long value) {
        return Math.max(0L, value);
    }

    private static ThreadPoolExecutor databaseExecutor() {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(512),
                runnable -> {
                    final Thread thread = new Thread(
                            runnable, "Sweats-Currency-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private record BalanceKey(UUID player, CurrencyType currency) {}
}
