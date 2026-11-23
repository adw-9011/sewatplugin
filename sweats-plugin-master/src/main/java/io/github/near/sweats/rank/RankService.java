package io.github.near.sweats.rank;

import io.github.near.sweats.rank.RankStore.StoredAssignment;
import io.github.near.sweats.rank.RankStore.StoredRank;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Owns core and custom ranks, player assignments and their YAML representation.
 *
 * <p>Player assignments are keyed by UUID and read from a concurrent map, so the asynchronous chat
 * pipeline can look up a rank without touching a mutable snapshot. Lifecycle assignments are
 * coalesced onto a single writer thread; administrative changes save synchronously.
 */
public final class RankService implements Listener, AutoCloseable {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9&][A-Za-z0-9 _&+-]{0,31}");
    private static final String DEFAULT_KEY = Rank.key("Default");
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;
    private static final long SHUTDOWN_NOW_TIMEOUT_SECONDS = 2L;
    private static final List<Rank> CORE_RANKS = List.of(
            new Rank("Default", Component.text("Default", NamedTextColor.GRAY), 0, true),
            new Rank("Grinder", Component.text("Grinder", NamedTextColor.GREEN), 10, true),
            new Rank("Sweat", Component.text("Sweat", NamedTextColor.BLUE), 20, true),
            new Rank("Sweat+", Component.text("Sweat+", NamedTextColor.LIGHT_PURPLE), 30, true),
            new Rank("Custom", RankFormat.parse("&6&lCustom"), 35, true),
            new Rank("Investor", RankFormat.parse("&e&lInvestor"), 38, true),
            new Rank("Admin", RankFormat.parse("&c&lAdmin"), 40, true),
            new Rank("CM", RankFormat.parse("&c&lCM"), 50, true),
            new Rank("DEV", Component.text("DEV", TextColor.color(0xFF8C00)), 60, true));

    private final RankStore store;
    private final Consumer<String> warn;
    private final List<Consumer<UUID>> changeListeners = new ArrayList<>();
    private final List<Runnable> rankListListeners = new ArrayList<>();
    private final Object writeLock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean writeScheduled = new AtomicBoolean();

    private volatile Map<String, Rank> ranks = new ConcurrentHashMap<>();
    private volatile ExecutorService writer;
    private volatile boolean closed;
    private volatile List<Rank> sortedRanks = List.of();
    private volatile Rank defaultRank;
    private int version;

    public RankService(final Path folder, final Consumer<String> warn) {
        this.store = new RankStore(folder.resolve("ranks.yml"));
        this.warn = warn;
        load();
    }

    public Rank defaultRank() {
        return defaultRank;
    }

    public List<Rank> ranks() {
        return sortedRanks;
    }

    public Optional<Rank> find(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ranks.get(Rank.key(name)));
    }

    public Rank rankOf(final UUID id) {
        final StoredAssignment assignment = store.players().get(id);
        if (assignment == null) {
            return defaultRank;
        }
        // Expiry is persisted by the periodic sweep on the main thread. Reads never write YAML
        // and never notify listeners from an arbitrary (possibly asynchronous) caller.
        if (isExpired(assignment, Instant.now())) {
            return defaultRank;
        }
        return rankFor(assignment);
    }

    /**
     * Clears expired assignments and notifies rank listeners.
     *
     * <p>Must run on the main thread because listeners update scoreboards and open menus. The
     * write itself is coalesced onto the rank writer thread, so the caller never blocks on disk.
     *
     * @return the number of assignments that were reset
     */
    public int expireAssignments() {
        final Instant now = Instant.now();
        final List<UUID> expired = new ArrayList<>();
        for (final Map.Entry<UUID, StoredAssignment> entry : store.players().entrySet()) {
            if (isExpired(entry.getValue(), now)) {
                expired.add(entry.getKey());
            }
        }
        if (expired.isEmpty()) {
            return 0;
        }

        int reset = 0;
        for (final UUID id : expired) {
            final StoredAssignment current = store.players().get(id);
            if (current == null || !isExpired(current, now)) {
                continue;
            }
            store.putPlayer(id, new StoredAssignment(current.name(), defaultRank.key(), null));
            reset++;
            if (!current.rankKey().equals(defaultRank.key())) {
                notifyChanged(id);
            }
        }
        if (reset > 0) {
            scheduleSave();
        }
        return reset;
    }

    private static boolean isExpired(final StoredAssignment assignment, final Instant now) {
        return assignment.expiresAt() != null && now.isAfter(assignment.expiresAt());
    }

    public Optional<KnownPlayer> knownPlayer(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (final Map.Entry<UUID, StoredAssignment> entry : store.players().entrySet()) {
            final String stored = entry.getValue().name();
            if (stored.equalsIgnoreCase(name)) {
                return Optional.of(new KnownPlayer(entry.getKey(), stored, rankFor(entry.getValue())));
            }
        }
        return Optional.empty();
    }

    public List<String> assignedPlayers(final Rank rank) {
        return store.players().entrySet().stream()
                .filter(entry -> rank.equals(rankFor(entry.getValue())))
                .map(entry -> entry.getValue().name())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public int assignedCount(final Rank rank) {
        int count = 0;
        for (final StoredAssignment assignment : store.players().values()) {
            if (rank.equals(rankFor(assignment))) {
                count++;
            }
        }
        return count;
    }

    public int version() {
        return version;
    }

    /**
     * Creates a new custom rank.
     *
     * @param requestedName the rank name, may contain legacy formatting codes (&0-9, &a-f, &k-o, &r)
     * @param weight the rank weight (controls ordering and priority)
     * @return the created rank
     */
    public Rank create(final String requestedName, final int weight) {
        final String rawName = validateName(requestedName);
        final String name = RankFormat.stripCodes(rawName);
        final String key = Rank.key(name);
        if (ranks.containsKey(key)) {
            throw new IllegalArgumentException("A rank named " + name + " already exists.");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("Rank weight must be zero or greater.");
        }
        if (isProtectedWeight(weight)) {
            throw new IllegalArgumentException("That weight is reserved for system ranks.");
        }

        final Component displayName = RankFormat.parse(rawName);
        final Rank rank = new Rank(name, displayName, weight, false);
        ranks.put(key, rank);
        store.putRank(key, new StoredRank(rank.name(), rank.displayName(), rank.weight()));
        saveNow();
        publishRanks();
        version++;
        notifyRankListChanged();
        return rank;
    }

    /** Deletes a custom rank. Core ranks cannot be deleted. */
    public void delete(final String requestedName) {
        final Rank rank = find(requestedName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rank: " + requestedName));
        if (rank.core()) {
            throw new IllegalArgumentException("Core system ranks cannot be deleted.");
        }

        ranks.remove(rank.key());
        store.removeRank(rank.key());

        for (final Map.Entry<UUID, StoredAssignment> entry : store.players().entrySet()) {
            if (entry.getValue().rankKey().equals(rank.key())) {
                store.putPlayer(
                        entry.getKey(),
                        new StoredAssignment(entry.getValue().name(), defaultRank.key(), null));
            }
        }

        saveNow();
        publishRanks();
        version++;
        notifyRankListChanged();
    }

    /**
     * Assigns a rank with optional expiration.
     *
     * @return {@code true} when the stored assignment changed
     */
    public boolean assign(final UUID id, final String playerName, final String requestedRank) {
        return assign(id, playerName, requestedRank, null);
    }

    /**
     * Assigns a rank with optional expiration.
     *
     * @param expiration expiration timestamp, or {@code null} for permanent
     * @return {@code true} when the stored assignment changed
     */
    public boolean assign(final UUID id, final String playerName, final String requestedRank, final Instant expiration) {
        final Rank rank = find(requestedRank)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rank: " + requestedRank));
        return apply(id, playerName, rank, true, expiration);
    }

    public void reload() {
        if (!flush()) {
            warn.accept("Skipped rank reload because pending changes could not be saved.");
            return;
        }
        load();
    }

    public void addChangeListener(final Consumer<UUID> listener) {
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void addRankListListener(final Runnable listener) {
        rankListListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        ensureAssigned(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        ensureAssigned(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        final ExecutorService executor = writer;
        boolean stopped = true;
        if (executor != null) {
            executor.shutdown();
            stopped = awaitTermination(executor, SHUTDOWN_TIMEOUT_SECONDS);
            if (!stopped) {
                executor.shutdownNow();
                stopped = awaitTermination(executor, SHUTDOWN_NOW_TIMEOUT_SECONDS);
            }
        }

        if (stopped) {
            flush();
        } else {
            warn.accept("Skipped the final rank save because the writer did not stop; "
                    + "pending changes may not be persisted.");
        }
        writer = null;
    }

    /** Ensures a player has an explicit assignment at both ends of a session. */
    boolean ensureAssigned(final UUID id, final String playerName) {
        final StoredAssignment current = store.players().get(id);
        if (current == null) {
            return apply(id, playerName, defaultRank, false, null);
        }
        final boolean expired = isExpired(current, Instant.now());
        final Rank rank = expired ? defaultRank : rankFor(current);
        if (!expired && current.name().equals(playerName) && current.rankKey().equals(rank.key())) {
            return false;
        }
        return apply(id, playerName, rank, false, expired ? null : current.expiresAt());
    }

    /** Writes pending lifecycle changes; returns {@code false} when the data is still dirty. */
    boolean flush() {
        synchronized (writeLock) {
            if (!dirty.getAndSet(false)) {
                return true;
            }
            if (saveRankState()) {
                return true;
            }
            dirty.set(true);
            return false;
        }
    }

    private boolean apply(
            final UUID id, final String playerName, final Rank rank, final boolean immediate, final Instant expiration) {
        final StoredAssignment current = store.players().get(id);
        final boolean rankChanged = current == null || !current.rankKey().equals(rank.key());
        final boolean nameChanged = current == null || !current.name().equals(playerName);
        final boolean expirationChanged = current == null || !Objects.equals(current.expiresAt(), expiration);
        if (!rankChanged && !nameChanged && !expirationChanged) {
            return false;
        }

        store.putPlayer(id, new StoredAssignment(playerName, rank.key(), expiration));
        if (immediate) {
            saveNow();
        } else {
            scheduleSave();
        }
        if (rankChanged) {
            notifyChanged(id);
        }
        return true;
    }

    private Rank rankFor(final StoredAssignment assignment) {
        if (isExpired(assignment, Instant.now())) {
            return defaultRank;
        }
        final Rank rank = ranks.get(assignment.rankKey());
        return rank == null ? defaultRank : rank;
    }

    private void load() {
        store.load(warn);
        final Map<String, Rank> loaded = new ConcurrentHashMap<>();
        for (final Rank rank : CORE_RANKS) {
            loaded.put(rank.key(), rank);
        }
        defaultRank = loaded.get(DEFAULT_KEY);

        boolean repaired = false;
        for (final Map.Entry<String, StoredRank> entry : store.ranks().entrySet()) {
            final String key = entry.getKey();
            if (loaded.containsKey(key)) {
                warn.accept("Ignored custom rank '" + entry.getValue().name()
                        + "' because it duplicates a core rank.");
                store.removeRank(key);
                repaired = true;
                continue;
            }
            final StoredRank stored = entry.getValue();
            loaded.put(key, new Rank(stored.name(), stored.displayName(), stored.weight(), false));
        }

        for (final Map.Entry<UUID, StoredAssignment> entry : store.players().entrySet()) {
            final StoredAssignment assignment = entry.getValue();
            if (loaded.containsKey(assignment.rankKey())) {
                continue;
            }
            warn.accept("Reassigned " + assignment.name() + " to Default because rank '"
                    + assignment.rankKey() + "' no longer exists.");
            store.putPlayer(
                    entry.getKey(), new StoredAssignment(assignment.name(), defaultRank.key(), null));
            repaired = true;
        }

        ranks = loaded;
        publishRanks();
        version++;
        if (repaired) {
            saveNow();
        }
    }

    private void publishRanks() {
        final List<Rank> sorted = new ArrayList<>(ranks.values());
        sorted.sort(null);
        sortedRanks = List.copyOf(sorted);
    }

    private void saveNow() {
        synchronized (writeLock) {
            dirty.set(false);
            if (!saveRankState()) {
                dirty.set(true);
            }
        }
    }

    private void scheduleSave() {
        dirty.set(true);
        if (closed) {
            return;
        }
        if (writeScheduled.compareAndSet(false, true)) {
            try {
                writer().execute(this::writeNow);
            } catch (final RejectedExecutionException exception) {
                writeScheduled.set(false);
                warn.accept("Could not queue a rank save: " + exception.getMessage());
            }
        }
    }

    private void writeNow() {
        writeScheduled.set(false);
        if (closed) {
            return;
        }

        final boolean saved;
        synchronized (writeLock) {
            if (!dirty.compareAndSet(true, false)) {
                return;
            }
            saved = saveRankState();
            if (!saved) {
                dirty.set(true);
            }
        }
        if (saved && dirty.get()) {
            scheduleSave();
        }
    }

    private boolean saveRankState() {
        try {
            return store.save(warn);
        } catch (final RuntimeException exception) {
            warn.accept("Could not save ranks: " + exception.getMessage());
            return false;
        }
    }

    private static boolean awaitTermination(final ExecutorService executor, final long seconds) {
        try {
            return executor.awaitTermination(seconds, TimeUnit.SECONDS);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ExecutorService writer() {
        final ExecutorService current = writer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (writer == null) {
                writer = Executors.newSingleThreadExecutor(runnable -> {
                    final Thread thread = new Thread(runnable, "Sweats rank saves");
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((source, error) ->
                            warn.accept("Rank save thread failed: " + error));
                    return thread;
                });
            }
            return writer;
        }
    }

    private void notifyChanged(final UUID id) {
        for (final Consumer<UUID> listener : changeListeners) {
            listener.accept(id);
        }
    }

    private void notifyRankListChanged() {
        for (final Runnable listener : rankListListeners) {
            listener.run();
        }
    }

    private static String validateName(final String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Rank name is required.");
        }
        final String name = raw.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Rank name cannot be empty.");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Rank names may contain letters, numbers, spaces, '_', '+', '-', and '&' formatting codes.");
        }
        return name;
    }

    private boolean isProtectedWeight(final int weight) {
        // Weights 35+ are the donor and staff bands: a custom rank there would receive the
        // Custom/Investor perk tier or outrank staff merely by choosing a weight. Custom ranks
        // are limited to the weights below the first paid tier.
        return weight >= 35;
    }

    public record KnownPlayer(UUID id, String name, Rank rank) {}
}