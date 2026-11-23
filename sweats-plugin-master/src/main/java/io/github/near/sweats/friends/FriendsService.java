package io.github.near.sweats.friends;

import io.github.near.sweats.commands.PlayerLookup;
import io.github.near.sweats.owner.Owner;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.vanish.VanishService;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Hypixel-style friends system with requests, best friends, nicknames, notifications and preferences.
 *
 * <p>Every SQLite call runs on one bounded database worker. Public operations return a future that
 * is completed on the server thread; all Bukkit and Adventure player interaction happens there.
 * Requests, friend limits, reciprocal requests, accepts, denies and notification writes are
 * validated and committed together in the store.
 */
public final class FriendsService implements Listener, AutoCloseable {

    static final int DB_QUEUE_CAPACITY = 256;
    private static final long SHUTDOWN_WAIT_SECONDS = 3L;

    private final Plugin plugin;
    private final FriendsStore store;
    private final RankService ranks;
    private final Predicate<UUID> authenticated;
    private final PermissionService permissions;
    private final Consumer<String> warn;
    private final LongSupplier clock;
    private final Executor databaseExecutor;
    private final ExecutorService owningExecutor;
    private final Consumer<Runnable> mainDispatcher;
    private final Function<UUID, Player> onlinePlayer;
    private final Predicate<UUID> canSeeVanished;

    private final java.util.Map<UUID, Long> sessionGeneration = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> requestCooldowns = new ConcurrentHashMap<>();

    private volatile FriendsSettings settings;
    private volatile boolean closed;
    private volatile boolean storeReady;

    public FriendsService(
            final Plugin plugin,
            final Path folder,
            final RankService ranks,
            final PermissionService permissions,
            final Predicate<UUID> authenticated,
            final Consumer<String> warn,
            final VanishService vanishService) {
        final Function<UUID, Player> playerLookup = Bukkit::getPlayer;
        final Predicate<UUID> canSeeVanished = vanishService != null
                ? uuid -> {
                    final Player player = playerLookup.apply(uuid);
                    return player != null && vanishService.canSeeVanished(player);
                }
                : uuid -> false;
        this(
                plugin,
                ranks,
                permissions,
                authenticated,
                warn,
                System::currentTimeMillis,
                new FriendsStore(folder.resolve("friends.db"), warn),
                boundedExecutor(),
                null,
                playerLookup,
                canSeeVanished);
        openStore();
    }

    /** Test/integration constructor with an already-open store and explicit executors. */
    public FriendsService(
            final Plugin plugin,
            final RankService ranks,
            final PermissionService permissions,
            final Predicate<UUID> authenticated,
            final Consumer<String> warn,
            final LongSupplier clock,
            final FriendsStore store,
            final Executor databaseExecutor,
            final Consumer<Runnable> mainDispatcher,
            final Function<UUID, Player> onlinePlayer) {
        this(
                plugin,
                ranks,
                permissions,
                authenticated,
                warn,
                clock,
                store,
                databaseExecutor,
                mainDispatcher,
                onlinePlayer,
                uuid -> false);
    }

    /** Test/integration constructor with an already-open store, explicit executors, and vanish visibility predicate. */
    public FriendsService(
            final Plugin plugin,
            final RankService ranks,
            final PermissionService permissions,
            final Predicate<UUID> authenticated,
            final Consumer<String> warn,
            final LongSupplier clock,
            final FriendsStore store,
            final Executor databaseExecutor,
            final Consumer<Runnable> mainDispatcher,
            final Function<UUID, Player> onlinePlayer,
            final Predicate<UUID> canSeeVanished) {
        this(
                plugin,
                ranks,
                permissions,
                authenticated,
                warn,
                clock,
                store,
                new ExecutorBundle(databaseExecutor, null),
                mainDispatcher,
                onlinePlayer,
                canSeeVanished);
        this.storeReady = store.isOpen();
    }

    private FriendsService(
            final Plugin plugin,
            final RankService ranks,
            final PermissionService permissions,
            final Predicate<UUID> authenticated,
            final Consumer<String> warn,
            final LongSupplier clock,
            final FriendsStore store,
            final ExecutorBundle bundle,
            final Consumer<Runnable> mainDispatcher,
            final Function<UUID, Player> onlinePlayer,
            final Predicate<UUID> canSeeVanished) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.ranks = Objects.requireNonNull(ranks, "ranks");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.authenticated = Objects.requireNonNull(authenticated, "authenticated");
        this.warn = Objects.requireNonNull(warn, "warn");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.databaseExecutor = Objects.requireNonNull(bundle.executor(), "databaseExecutor");
        this.owningExecutor = bundle.owner();
        this.onlinePlayer = Objects.requireNonNull(onlinePlayer, "onlinePlayer");
        this.canSeeVanished = Objects.requireNonNull(canSeeVanished, "canSeeVanished");
        this.settings = FriendsSettings.from(plugin.getConfig());
        this.mainDispatcher = mainDispatcher != null
                ? mainDispatcher
                : task -> {
                    if (!closed && plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, task);
                    }
                };
    }

    private void openStore() {
        execute("open friends.db", () -> {
            try {
                store.open();
                storeReady = true;
            } catch (final SQLException exception) {
                warn.accept("Could not open friends.db - the friends system is unavailable: "
                        + exception.getMessage());
            }
        });
    }

    /**
     * Attempts to reopen the friends database if it previously failed to open.
     * Called automatically on reload, or can be invoked manually.
     */
    public void reopenStore() {
        if (storeReady) {
            return;
        }
        execute("reopen friends.db", () -> {
            try {
                store.open();
                storeReady = true;
                warn.accept("Friends database reopened successfully after previous failure.");
            } catch (final SQLException exception) {
                warn.accept("Friends database reopen failed: " + exception.getMessage());
            }
        });
    }

    private static ExecutorBundle boundedExecutor() {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DB_QUEUE_CAPACITY),
                runnable -> {
                    final Thread thread = new Thread(runnable, "Sweats-FriendsDB");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        return new ExecutorBundle(executor, executor);
    }

    public void reload() {
        this.settings = FriendsSettings.from(plugin.getConfig());
        reopenStore();
    }

    public boolean isReady() {
        return storeReady;
    }

    @Override
    public void close() {
        closed = true;
        storeReady = false;
        if (owningExecutor != null) {
            owningExecutor.shutdownNow();
            try {
                if (!owningExecutor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    warn.accept("A friends database task did not stop within "
                            + SHUTDOWN_WAIT_SECONDS + " seconds; closing friends.db anyway.");
                }
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        store.close();
        sessionGeneration.clear();
        requestCooldowns.clear();
    }

    // ------------------------------------------------------------------------------------------
    // Friend Requests

    /**
     * Sends a friend request from {@code requester} to {@code target}.
     *
     * <p>The future completes on the server thread. Its result is only the sending player's
     * feedback; the target notification is sent by this service after the database commit.
     */
    public CompletableFuture<Result> sendRequest(final Player requester, final Player target) {
        if (requester == null || target == null) {
            return CompletableFuture.completedFuture(Result.error("Unknown player."));
        }
        final UUID requesterId = requester.getUniqueId();
        final UUID targetId = target.getUniqueId();
        if (requesterId.equals(targetId)) {
            return CompletableFuture.completedFuture(Result.error("You cannot friend yourself."));
        }
        if (!authenticated.test(requesterId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        if (!target.isOnline() || !authenticated.test(targetId)) {
            return CompletableFuture.completedFuture(Result.error("That player is not logged in."));
        }
        // Require both base friend permission and requests permission to ensure
        // the target can actually manage incoming requests via /friend accept/deny
        if (!permissions.has(target, PermissionNodes.FRIEND)
                || !permissions.has(target, PermissionNodes.FRIEND_REQUESTS)) {
            return CompletableFuture.completedFuture(
                    Result.error("That player cannot receive friend requests."));
        }
        if (Owner.isOwner(targetId) && !Owner.isOwner(requesterId)) {
            return CompletableFuture.completedFuture(
                    Result.error("You cannot friend the server owner."));
        }

        final FriendsSettings current = settings;
        final long now = clock.getAsLong();
        pruneCooldowns(now);
        // Ensure cooldown cache is synced with persistent store
        if (!requestCooldowns.containsKey(requesterId)) {
            syncCooldownFromStore(requesterId);
        }
        final long remaining = cooldownRemaining(requesterId, now);
        if (remaining > 0L) {
            return CompletableFuture.completedFuture(Result.error(
                    "Wait " + remaining + "s before sending another friend request."));
        }

        final String requesterName = requester.getName();
        final String targetName = target.getName();
        final long expiresAt = now + current.requestExpireHours() * 60L * 60L * 1_000L;
        final Component requestMessage = buildRequestMessage(requester);
        return defer("send friend request", () -> {
            final FriendsStore.RequestResult request = store.createRequest(
                    requesterId,
                    requesterName,
                    targetId,
                    targetName,
                    now,
                    expiresAt,
                    current.maxFriends(),
                    current.defaultRequestsEnabled());
            return () -> finishSendRequest(
                    requester,
                    target,
                    request,
                    requestMessage,
                    now,
                    current);
        });
    }

    private Result finishSendRequest(
            final Player requester,
            final Player target,
            final FriendsStore.RequestResult request,
            final Component requestMessage,
            final long now,
            final FriendsSettings current) {
        final String targetName = target.getName();
        switch (request.outcome()) {
            case CREATED -> {
                startCooldown(requester.getUniqueId(), now, current);
                if (target.isOnline() && authenticated.test(target.getUniqueId())) {
                    target.sendMessage(requestMessage);
                }
                return Result.ok("Friend request sent to " + targetName + ".");
            }
            case RECIPROCAL_ACCEPTED -> {
                startCooldown(requester.getUniqueId(), now, current);
                if (target.isOnline() && authenticated.test(target.getUniqueId())) {
                    target.sendMessage(Component.text(
                            "You are now friends with " + requester.getName() + ".",
                            NamedTextColor.GREEN));
                }
                return Result.ok("You are now friends with " + targetName + ".");
            }
            case SELF -> {
                return Result.error("You cannot friend yourself.");
            }
            case ALREADY_FRIENDS -> {
                return Result.error("You are already friends with " + targetName + ".");
            }
            case ALREADY_REQUESTED -> {
                return Result.error("You already have a pending friend request to " + targetName + ".");
            }
            case OUTGOING_PENDING -> {
                return Result.error("You already have a pending friend request. "
                        + "Wait for it to be accepted or denied.");
            }
            case REQUESTER_FULL -> {
                return Result.error("Your friends list is full.");
            }
            case TARGET_FULL -> {
                return Result.error("That player's friends list is full.");
            }
            case TARGET_DISABLED -> {
                return Result.error("That player has disabled friend requests.");
            }
            default -> {
                return Result.error("Could not send the friend request. Try again.");
            }
        }
    }

    /**
     * Accepts a friend request from {@code requesterName}.
     */
    public CompletableFuture<Result> acceptRequest(
            final Player target, final String requesterName) {
        if (target == null || requesterName == null || requesterName.isBlank()) {
            return CompletableFuture.completedFuture(Result.error("Unknown player."));
        }
        final Optional<PlayerLookup.Target> requester = PlayerLookup.find(requesterName, target);
        if (requester.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Result.error("Unknown player: " + requesterName));
        }
        return acceptRequest(target, requester.get().id(), requester.get().name());
    }

    CompletableFuture<Result> acceptRequest(
            final Player target, final UUID requesterId, final String requesterName) {
        final UUID targetId = target.getUniqueId();
        if (!authenticated.test(targetId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        if (requesterId == null || requesterId.equals(targetId)) {
            return CompletableFuture.completedFuture(
                    Result.error("You cannot accept your own friend request."));
        }
        final FriendsSettings current = settings;
        final long now = clock.getAsLong();
        final String targetName = target.getName();
        final String safeRequesterName = requesterName == null || requesterName.isBlank()
                ? shortId(requesterId)
                : requesterName;
        return defer("accept friend request", () -> {
            final FriendsStore.AcceptResult accepted = store.acceptRequest(
                    requesterId,
                    safeRequesterName,
                    targetId,
                    targetName,
                    now,
                    current.maxFriends());
            return () -> finishAccept(
                    target, requesterId, safeRequesterName, targetName, accepted, now);
        });
    }

    private Result finishAccept(
            final Player target,
            final UUID requesterId,
            final String requesterName,
            final String targetName,
            final FriendsStore.AcceptResult accepted,
            final long now) {
        switch (accepted.outcome()) {
            case ACCEPTED -> {
                notifyRequester(
                        requesterId,
                        Component.text(
                                targetName + " has accepted your friend request!",
                                NamedTextColor.GREEN),
                        accepted.notificationId(),
                        now);
                return Result.ok("You have accepted " + requesterName + "'s friend request.");
            }
            case NO_REQUEST -> {
                return Result.error("No friend request from " + requesterName + ".");
            }
            case EXPIRED -> {
                return Result.error("That friend request has expired.");
            }
            case ALREADY_FRIENDS -> {
                return Result.error("You are already friends with " + requesterName + ".");
            }
            case REQUESTER_FULL -> {
                return Result.error("That player's friends list is full.");
            }
            case TARGET_FULL -> {
                return Result.error("Your friends list is full.");
            }
            default -> {
                return Result.error("Could not accept the friend request. Try again.");
            }
        }
    }

    /**
     * Denies a friend request from {@code requesterName}.
     */
    public CompletableFuture<Result> denyRequest(
            final Player target, final String requesterName) {
        if (target == null || requesterName == null || requesterName.isBlank()) {
            return CompletableFuture.completedFuture(Result.error("Unknown player."));
        }
        final Optional<PlayerLookup.Target> requester = PlayerLookup.find(requesterName, target);
        if (requester.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Result.error("Unknown player: " + requesterName));
        }
        return denyRequest(target, requester.get().id(), requester.get().name());
    }

    CompletableFuture<Result> denyRequest(
            final Player target, final UUID requesterId, final String requesterName) {
        final UUID targetId = target.getUniqueId();
        if (!authenticated.test(targetId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        if (requesterId == null || requesterId.equals(targetId)) {
            return CompletableFuture.completedFuture(
                    Result.error("You cannot deny your own friend request."));
        }
        final long now = clock.getAsLong();
        final String targetName = target.getName();
        final String safeRequesterName = requesterName == null || requesterName.isBlank()
                ? shortId(requesterId)
                : requesterName;
        return defer("deny friend request", () -> {
            final FriendsStore.DenyResult denied = store.denyRequest(
                    requesterId, targetId, targetName, now);
            return () -> finishDeny(
                    requesterId, safeRequesterName, targetName, denied, now);
        });
    }

    private Result finishDeny(
            final UUID requesterId,
            final String requesterName,
            final String targetName,
            final FriendsStore.DenyResult denied,
            final long now) {
        switch (denied.outcome()) {
            case DENIED -> {
                notifyRequester(
                        requesterId,
                        Component.text(
                                targetName + " has denied your friend request.",
                                NamedTextColor.RED),
                        denied.notificationId(),
                        now);
                return Result.ok("You have denied " + requesterName + "'s friend request.");
            }
            case NO_REQUEST -> {
                return Result.error("No friend request from " + requesterName + ".");
            }
            case EXPIRED -> {
                return Result.error("That friend request has expired.");
            }
            default -> {
                return Result.error("Could not deny the friend request. Try again.");
            }
        }
    }

    /**
     * Removes a friend and every decoration tied to the friendship.
     */
    public CompletableFuture<Result> removeFriend(final Player player, final String targetName) {
        if (player == null || targetName == null || targetName.isBlank()) {
            return CompletableFuture.completedFuture(Result.error("Unknown player."));
        }
        final Optional<PlayerLookup.Target> target = PlayerLookup.find(targetName, player);
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(Result.error("Unknown player: " + targetName));
        }
        return removeFriend(player, target.get().id(), target.get().name());
    }

    CompletableFuture<Result> removeFriend(
            final Player player, final UUID targetId, final String targetName) {
        final UUID playerId = player.getUniqueId();
        if (!authenticated.test(playerId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        if (targetId == null || targetId.equals(playerId)) {
            return CompletableFuture.completedFuture(
                    Result.error("You cannot remove yourself from your friends."));
        }
        final String playerName = player.getName();
        return defer("remove friend", () -> {
            final boolean removed = store.removeFriendship(playerId, targetId);
            return () -> {
                if (!removed) {
                    return Result.error("You are not friends with " + targetName + ".");
                }
                notifyOnline(
                        targetId,
                        Component.text(
                                playerName + " has removed you from their friends list.",
                                NamedTextColor.RED));
                return Result.ok("You have removed " + targetName + " from your friends.");
            };
        });
    }

    // ------------------------------------------------------------------------------------------
    // Friend List

    public CompletableFuture<Result> openFriendList(final Player player, final int page) {
        final UUID playerId = player.getUniqueId();
        if (!authenticated.test(playerId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        final int requestedPage = Math.max(1, page);
        return defer("load friend list", () -> {
            final List<FriendsStore.Friendship> friendships = store.getFriendships(playerId);
            return () -> presentFriendList(player, friendships, requestedPage);
        });
    }

    /**
     * Lists all pending incoming friend requests for a player.
     */
    public CompletableFuture<Result> listIncomingRequests(final Player player) {
        final UUID playerId = player.getUniqueId();
        if (!authenticated.test(playerId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        return defer("load incoming requests", () -> {
            final List<FriendsStore.FriendRequest> requests = store.getIncomingRequests(playerId);
            return () -> presentIncomingRequests(player, requests);
        });
    }

    private Result presentIncomingRequests(final Player player, final List<FriendsStore.FriendRequest> requests) {
        if (!player.isOnline() || !authenticated.test(player.getUniqueId())) {
            return Result.handled();
        }
        final List<Component> messages = new ArrayList<>();
        if (requests.isEmpty()) {
            messages.add(Component.text("You have no pending friend requests.", NamedTextColor.GRAY));
            return new Result(true, messages);
        }
        messages.add(Component.text("Pending friend requests:", NamedTextColor.YELLOW));
        for (final FriendsStore.FriendRequest request : requests) {
            final String requesterName = store.getNickname(request.target(), request.requester())
                    .orElseGet(() -> store.getFriendships(request.target()).stream()
                            .filter(f -> f.friendId().equals(request.requester()))
                            .map(FriendsStore.Friendship::friendName)
                            .findFirst()
                            .orElseGet(() -> shortId(request.requester())));
            messages.add(Component.text()
                    .append(Component.text("  " + requesterName, NamedTextColor.AQUA))
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                    .append(link("[ACCEPT]", NamedTextColor.GREEN,
                            "/f accept " + requesterName, "Accept request from " + requesterName))
                    .append(Component.text(" ", NamedTextColor.DARK_GRAY))
                    .append(link("[DENY]", NamedTextColor.RED,
                            "/f deny " + requesterName, "Deny request from " + requesterName))
                    .build());
        }
        messages.add(Component.text(
                "Use /f accept <name> or /f deny <name> to respond.", NamedTextColor.GRAY));
        return new Result(true, messages);
    }

    private Result presentFriendList(
            final Player player,
            final List<FriendsStore.Friendship> friendships,
            final int page) {
        if (!player.isOnline() || !authenticated.test(player.getUniqueId())) {
            return Result.handled();
        }
        if (friendships.isEmpty()) {
            return Result.ok("You have no friends yet.");
        }

        final List<FriendEntry> onlineEntries = new ArrayList<>();
        final List<FriendEntry> offlineEntries = new ArrayList<>();
        for (final FriendsStore.Friendship friendship : friendships) {
            final Player friend = onlinePlayer.apply(friendship.friendId());
            final boolean online = friend != null
                    && friend.isOnline()
                    && authenticated.test(friendship.friendId())
                    && canSeeVanished.test(friendship.friendId());
            final String fallback = online && friend.getName() != null
                    && !friend.getName().isBlank()
                    ? friend.getName()
                    : shortId(friendship.friendId());
            final String name = friendship.friendName() == null || friendship.friendName().isBlank()
                    ? fallback
                    : friendship.friendName();
            final FriendEntry entry = new FriendEntry(
                    friendship.friendId(),
                    name,
                    friendship.bestFriend(),
                    friendship.nickname(),
                    online);
            if (online) {
                onlineEntries.add(entry);
            } else {
                offlineEntries.add(entry);
            }
        }

        final Comparator<FriendEntry> sorter = Comparator
                .comparing((FriendEntry entry) -> !entry.bestFriend())
                .thenComparing(FriendEntry::name, String.CASE_INSENSITIVE_ORDER);
        onlineEntries.sort(sorter);
        offlineEntries.sort(sorter);

        final List<FriendEntry> combined = new ArrayList<>(onlineEntries.size() + offlineEntries.size());
        combined.addAll(onlineEntries);
        combined.addAll(offlineEntries);

        final FriendsSettings current = settings;
        final int pageSize = current.listPageSize();
        final int totalPages = Math.max(1, (combined.size() + pageSize - 1) / pageSize);
        final int currentPage = Math.min(page, totalPages);
        final int start = (currentPage - 1) * pageSize;
        final int end = Math.min(start + pageSize, combined.size());

        final List<Component> messages = new ArrayList<>();
        messages.add(Component.text()
                .append(Component.text("---------------- Friends (", NamedTextColor.DARK_GRAY))
                .append(Component.text(friendships.size(), NamedTextColor.YELLOW))
                .append(Component.text("/", NamedTextColor.GRAY))
                .append(Component.text(current.maxFriendsLabel(), NamedTextColor.YELLOW))
                .append(Component.text(") ----------------", NamedTextColor.DARK_GRAY))
                .build());

        boolean onlineHeader = false;
        boolean offlineHeader = false;
        for (int index = start; index < end; index++) {
            final FriendEntry entry = combined.get(index);
            if (entry.online() && !onlineHeader) {
                messages.add(Component.text("Online:", NamedTextColor.GREEN));
                onlineHeader = true;
            } else if (!entry.online() && !offlineHeader) {
                messages.add(Component.text("Offline:", NamedTextColor.GRAY));
                offlineHeader = true;
            }
            messages.add(formatFriendEntry(entry));
        }

        messages.add(Component.text(
                "-------------------------------------------------", NamedTextColor.DARK_GRAY));
        if (totalPages > 1) {
            final Component previous = currentPage > 1
                    ? Component.text("[Previous]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.suggestCommand("/f list " + (currentPage - 1)))
                            .hoverEvent(HoverEvent.showText(Component.text(
                                    "Previous page", NamedTextColor.GRAY)))
                    : Component.text("[Previous]", NamedTextColor.DARK_GRAY);
            final Component next = currentPage < totalPages
                    ? Component.text("[Next]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.suggestCommand("/f list " + (currentPage + 1)))
                            .hoverEvent(HoverEvent.showText(Component.text(
                                    "Next page", NamedTextColor.GRAY)))
                    : Component.text("[Next]", NamedTextColor.DARK_GRAY);
            messages.add(Component.text()
                    .append(previous)
                    .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                    .append(next)
                    .build());
        }
        return new Result(true, messages);
    }

    private Component formatFriendEntry(final FriendEntry entry) {
        final String name = entry.nickname() != null ? entry.nickname() : entry.name();
        final Component prefix = rankPrefix(entry.id());
        final String status = entry.online() ? " (online)" : "";
        return Component.text()
                .append(prefix)
                .append(Component.text(
                        name + status,
                        entry.bestFriend() ? NamedTextColor.GOLD : NamedTextColor.WHITE))
                .append(entry.bestFriend()
                        ? Component.text(" \u2605", NamedTextColor.GOLD)
                                .decoration(TextDecoration.BOLD, true)
                        : Component.empty())
                .build();
    }

    Component rankPrefix(final UUID uuid) {
        return ranks.rankOf(uuid).displayPrefix();
    }

    private static String shortId(final UUID uuid) {
        final String value = uuid.toString();
        return value.substring(0, 8);
    }

    // ------------------------------------------------------------------------------------------
    // Best Friends

    public CompletableFuture<Result> toggleBestFriend(final Player player, final String targetName) {
        if (player == null || targetName == null || targetName.isBlank()) {
            return CompletableFuture.completedFuture(Result.error("Unknown player."));
        }
        final Optional<PlayerLookup.Target> target = PlayerLookup.find(targetName, player);
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(Result.error("Unknown player: " + targetName));
        }
        return toggleBestFriend(player, target.get().id(), target.get().name());
    }

    CompletableFuture<Result> toggleBestFriend(
            final Player player, final UUID targetId, final String targetName) {
        final UUID playerId = player.getUniqueId();
        if (!authenticated.test(playerId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        if (targetId == null || targetId.equals(playerId)) {
            return CompletableFuture.completedFuture(
                    Result.error("You can only set friends as best friends."));
        }
        final FriendsSettings current = settings;
        final long now = clock.getAsLong();
        return defer("toggle best friend", () -> {
            final FriendsStore.BestFriendOutcome outcome = store.toggleBestFriend(
                    playerId, targetId, now, current.maxBestFriends());
            return () -> switch (outcome) {
                case ADDED -> Result.ok(
                        "You have set " + targetName + " as your best friend.");
                case REMOVED -> Result.ok(
                        "You have removed " + targetName + " from your best friends.");
                case NOT_FRIENDS -> Result.error(
                        "You can only set friends as best friends.");
                case LIMIT -> Result.error(
                        "You can only have " + current.maxBestFriends() + " best friends.");
            };
        });
    }

    // ------------------------------------------------------------------------------------------
    // Friend Nicknames

    public CompletableFuture<Result> setNickname(
            final Player player, final String targetName, final String rawNickname) {
        if (player == null || targetName == null || targetName.isBlank()) {
            return CompletableFuture.completedFuture(Result.error("Unknown player."));
        }
        final Optional<PlayerLookup.Target> target = PlayerLookup.find(targetName, player);
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(Result.error("Unknown player: " + targetName));
        }
        return setNickname(player, target.get().id(), target.get().name(), rawNickname);
    }

    CompletableFuture<Result> setNickname(
            final Player player,
            final UUID targetId,
            final String targetName,
            final String rawNickname) {
        final UUID playerId = player.getUniqueId();
        if (!authenticated.test(playerId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        if (targetId == null || targetId.equals(playerId)) {
            return CompletableFuture.completedFuture(Result.error("You can only nickname friends."));
        }

        final String nickname = rawNickname == null ? "" : rawNickname.trim();
        if ("reset".equalsIgnoreCase(nickname)) {
            return defer("reset friend nickname", () -> {
                final FriendsStore.NicknameOutcome outcome =
                        store.removeNickname(playerId, targetId);
                return () -> switch (outcome) {
                    case REMOVED -> Result.ok("Removed nickname for " + targetName + ".");
                    case NOT_SET -> Result.ok(targetName + " did not have a nickname.");
                    case NOT_FRIENDS -> Result.error("You can only nickname friends.");
                    default -> Result.error("Could not reset the nickname. Try again.");
                };
            });
        }

        final String validationError = validateNickname(nickname);
        if (validationError != null) {
            return CompletableFuture.completedFuture(Result.error(validationError));
        }
        return defer("save friend nickname", () -> {
            final FriendsStore.NicknameOutcome outcome =
                    store.setNickname(playerId, targetId, nickname);
            return () -> switch (outcome) {
                case SET -> Result.ok(
                        "You have set " + targetName + "'s nickname to " + nickname + ".");
                case NOT_FRIENDS -> Result.error("You can only nickname friends.");
                default -> Result.error("Could not save the nickname. Try again.");
            };
        });
    }

    private String validateNickname(final String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "Nickname cannot be empty.";
        }
        if (nickname.length() > settings.maxNicknameLength()) {
            return "Nickname too long (max " + settings.maxNicknameLength() + " characters).";
        }
        for (int index = 0; index < nickname.length(); index++) {
            final char character = nickname.charAt(index);
            if (character == '&' || character == '\u00A7'
                    || Character.isISOControl(character)
                    || Character.getType(character) == Character.FORMAT
                    || character == '\u2028' || character == '\u2029') {
                return "Nickname contains invalid characters.";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Preferences

    public CompletableFuture<Result> toggleNotifications(final Player player) {
        final UUID playerId = player.getUniqueId();
        if (!authenticated.test(playerId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        final FriendsSettings current = settings;
        return defer("toggle notifications preference", () -> {
            final boolean enabled = store.toggleNotifications(
                    playerId,
                    current.defaultNotificationsEnabled(),
                    current.defaultRequestsEnabled());
            return () -> Result.ok(
                    "Friend notifications " + (enabled ? "enabled" : "disabled") + ".");
        });
    }

    public CompletableFuture<Result> toggleRequests(final Player player) {
        final UUID playerId = player.getUniqueId();
        if (!authenticated.test(playerId)) {
            return CompletableFuture.completedFuture(Result.error("You must log in first."));
        }
        final FriendsSettings current = settings;
        return defer("toggle requests preference", () -> {
            final boolean enabled = store.toggleRequests(
                    playerId,
                    current.defaultNotificationsEnabled(),
                    current.defaultRequestsEnabled());
            return () -> Result.ok(
                    "Friend requests " + (enabled ? "enabled" : "disabled") + ".");
        });
    }

    // ------------------------------------------------------------------------------------------
    // Session notifications

    /** Called when authentication succeeds, after the player is fully available. */
    public void onAuthenticated(final Player player) {
        beginSession(player);
    }

    /** Stops session-dependent notifications when a player logs out of their account. */
    public void onDeauthenticated(final Player player) {
        if (player != null) {
            sessionGeneration.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(final PlayerJoinEvent event) {
        // With auth disabled, or for the hardcoded owner, no auth listener fires.
        if (authenticated.test(event.getPlayer().getUniqueId())) {
            beginSession(event.getPlayer());
        }
    }

    private void beginSession(final Player player) {
        if (player == null
                || !player.isOnline()
                || !authenticated.test(player.getUniqueId())) {
            return;
        }
        final UUID playerId = player.getUniqueId();
        final long generation = sessionGeneration.merge(playerId, 1L, (old, ignored) -> old + 1);
        final String playerName = player.getName();
        final long now = clock.getAsLong();
        final FriendsSettings current = settings;
        final long retentionCutoff = now
                - current.notificationRetentionDays() * 24L * 60L * 60L * 1_000L;
        final long undeliveredRetentionCutoff = now
                - current.undeliveredNotificationRetentionDays() * 24L * 60L * 60L * 1_000L;
        // Prune undelivered notifications on session start
        execute("prune undelivered notifications", () -> store.pruneUndeliveredNotifications(undeliveredRetentionCutoff));
        final CompletableFuture<Result> session = defer("load friend session", () -> {
            final FriendsStore.SessionData data = store.startSession(
                    playerId,
                    playerName,
                    now,
                    retentionCutoff,
                    current.defaultNotificationsEnabled());
            return () -> presentSession(player, playerName, data, generation);
        });
        session.whenComplete((result, error) -> {
            if (error != null || result == null || !result.success()) {
                sessionGeneration.computeIfPresent(playerId, (id, gen) -> gen == generation ? null : gen);
            }
        });
    }

    private Result presentSession(
            final Player player,
            final String playerName,
            final FriendsStore.SessionData data,
            final long generation) {
        final UUID playerId = player.getUniqueId();
        if (!player.isOnline() || !authenticated.test(playerId)) {
            sessionGeneration.computeIfPresent(playerId, (id, gen) -> gen == generation ? null : gen);
            return Result.handled();
        }
        final Long currentGen = sessionGeneration.get(playerId);
        if (currentGen == null || currentGen != generation) {
            return Result.handled();
        }

        final List<Long> toDeliver = new ArrayList<>();
        for (final FriendsStore.FriendNotification notification : data.pendingNotifications()) {
            toDeliver.add(notification.id());
        }
        if (!toDeliver.isEmpty()) {
            final long now = clock.getAsLong();
            // Mark as delivered first to prevent duplicate delivery on queue saturation or retry
            store.markNotificationsDelivered(toDeliver, now);
            // Now send the messages (best effort)
            for (final FriendsStore.FriendNotification notification : data.pendingNotifications()) {
                player.sendMessage(notificationMessage(notification));
            }
        }

        sendPresence(data.presenceNotificationTargets(), playerName, true);
        return Result.handled();
    }

    // Before AuthService's MONITOR quit handler clears authentication, otherwise the leaving
    // player already looks unauthenticated and their friends never receive the leave message.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        sessionGeneration.remove(playerId);
        requestCooldowns.remove(playerId);
        if (!authenticated.test(playerId)) {
            return;
        }

        final String playerName = player.getName();
        final FriendsSettings current = settings;
        execute("load friend quit notification targets", () -> {
            final List<UUID> targets = store.getPresenceNotificationTargets(
                    playerId, current.defaultNotificationsEnabled());
            sync(() -> sendPresence(targets, playerName, false));
        });
    }

    private void sendPresence(
            final List<UUID> friendIds, final String playerName, final boolean joined) {
        if (friendIds == null || friendIds.isEmpty()) {
            return;
        }
        final String serverName = plugin.getConfig().getString("server.name", "the server");
        final Component message = Component.text()
                .append(Component.text(playerName, NamedTextColor.AQUA))
                .append(Component.text(
                        joined
                                ? " has joined " + serverName + "."
                                : " has left " + serverName + ".",
                        NamedTextColor.GRAY))
                .build();
        for (final UUID friendId : friendIds) {
            final Player friend = onlinePlayer.apply(friendId);
            if (friend != null
                    && friend.isOnline()
                    && authenticated.test(friendId)
                    && canSeeVanished.test(friendId)) {
                friend.sendMessage(message);
            }
        }
    }

    private Component notificationMessage(final FriendsStore.FriendNotification notification) {
        final String actor = notification.actorName() == null
                || notification.actorName().isBlank()
                ? shortId(notification.actor())
                : notification.actorName();
        return switch (notification.type()) {
            case REQUEST_ACCEPTED -> Component.text(
                    actor + " has accepted your friend request!", NamedTextColor.GREEN);
            case REQUEST_DENIED -> Component.text(
                    actor + " has denied your friend request.", NamedTextColor.RED);
        };
    }

    private void notifyOnline(final UUID recipientId, final Component message) {
        final Player recipient = onlinePlayer.apply(recipientId);
        if (recipient != null && recipient.isOnline() && authenticated.test(recipientId)) {
            recipient.sendMessage(message);
        }
    }

    private void notifyRequester(
            final UUID requesterId,
            final Component message,
            final long notificationId,
            final long now) {
        final Player requester = onlinePlayer.apply(requesterId);
        if (requester != null && requester.isOnline() && authenticated.test(requesterId)) {
            if (notificationId > 0L) {
                // Mark as delivered first to prevent duplicate delivery
                store.markNotificationsDelivered(List.of(notificationId), now);
            }
            requester.sendMessage(message);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers

    private void startCooldown(final UUID id, final long now, final FriendsSettings current) {
        if (current.requestCooldownSeconds() > 0) {
            final long expiresAt = now + current.requestCooldownSeconds() * 1_000L;
            requestCooldowns.put(id, expiresAt);
            execute("set request cooldown", () -> store.setRequestCooldown(id, expiresAt));
        }
    }

    private void pruneCooldowns(final long now) {
        requestCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        execute("prune expired cooldowns", () -> store.pruneExpiredCooldowns(now));
    }

    private long cooldownRemaining(final UUID id, final long now) {
        final Long until = requestCooldowns.get(id);
        if (until == null || until <= now) {
            requestCooldowns.remove(id);
            return 0L;
        }
        return Math.max(1L, (until - now + 999L) / 1_000L);
    }

    /**
     * Synchronizes the in-memory cooldown cache with the persistent store.
     * Called on startup and when cache may be stale.
     */
    private void syncCooldownFromStore(final UUID id) {
        execute("sync cooldown from store", () -> {
            final long remainingMs = store.getRequestCooldownRemaining(id, clock.getAsLong());
            if (remainingMs > 0) {
                requestCooldowns.put(id, clock.getAsLong() + remainingMs);
            } else {
                requestCooldowns.remove(id);
            }
        });
    }

    private Component buildRequestMessage(final Player requester) {
        final Component rank = rankPrefix(requester.getUniqueId());
        return Component.text()
                .append(Component.text(
                                "----------------------------------------------------",
                                NamedTextColor.BLUE)
                        .decoration(TextDecoration.STRIKETHROUGH, true))
                .append(Component.newline())
                .append(Component.text("Friend request from ", NamedTextColor.YELLOW))
                .append(rank)
                .append(Component.text(requester.getName(), NamedTextColor.AQUA))
                .append(Component.newline())
                .append(Component.text("Click one: ", NamedTextColor.YELLOW))
                .append(link(
                        "[ACCEPT]",
                        NamedTextColor.GREEN,
                        "/f accept " + requester.getName(),
                        "Accept friend request"))
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(link(
                        "[DENY]",
                        NamedTextColor.RED,
                        "/f deny " + requester.getName(),
                        "Deny friend request"))
                .append(Component.newline())
                .append(Component.text(
                                "----------------------------------------------------",
                                NamedTextColor.BLUE)
                        .decoration(TextDecoration.STRIKETHROUGH, true))
                .build();
    }

    private Component link(
            final String label,
            final NamedTextColor color,
            final String command,
            final String hover) {
        return Component.text(label, color)
                .decoration(TextDecoration.BOLD, true)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    private CompletableFuture<Result> defer(
            final String label, final Supplier<Supplier<Result>> work) {
        final CompletableFuture<Result> future = new CompletableFuture<>();
        if (closed) {
            future.complete(Result.error("The friends system is shutting down."));
            return future;
        }
        try {
            databaseExecutor.execute(() -> {
                final Supplier<Result> mainWork;
                try {
                    mainWork = work.get();
                } catch (final RuntimeException exception) {
                    warn.accept("Could not " + label + ": " + exception.getMessage());
                    complete(future, Result.error("Could not " + label + ". Try again."));
                    return;
                }
                if (!sync(() -> {
                    final Result result;
                    try {
                        result = mainWork.get();
                    } catch (final RuntimeException exception) {
                        warn.accept("Could not finish " + label + " on the server thread: "
                                + exception.getMessage());
                        future.complete(Result.error("Could not " + label + ". Try again."));
                        return;
                    }
                    future.complete(result);
                })) {
                    future.completeExceptionally(new IllegalStateException(
                            "Failed to dispatch completion to main thread; friends system may be shutting down"));
                }
            });
        } catch (final RejectedExecutionException exception) {
            future.complete(Result.error("The friends system is busy. Try again."));
        }
        return future;
    }

    private void execute(final String label, final Runnable task) {
        if (closed) {
            return;
        }
        try {
            databaseExecutor.execute(() -> {
                try {
                    task.run();
                } catch (final RuntimeException exception) {
                    warn.accept("Could not " + label + ": " + exception.getMessage());
                }
            });
        } catch (final RejectedExecutionException exception) {
            warn.accept("Friends database task rejected (" + label + "): queue full or shutting down.");
        }
    }

    private boolean sync(final Runnable task) {
        if (closed) {
            return false;
        }
        try {
            mainDispatcher.accept(task);
            return true;
        } catch (final RuntimeException exception) {
            warn.accept("Could not schedule a friends callback: " + exception.getMessage());
            return false;
        }
    }

    private void complete(final CompletableFuture<Result> future, final Result result) {
        if (!sync(() -> future.complete(result))) {
            future.completeExceptionally(new IllegalStateException(
                    "Failed to dispatch completion to main thread; friends system may be shutting down"));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Public API for commands and lifecycle integration

    /**
     * Removes every Friends row that references an account. The caller is the auth unregister
     * path; the database work is queued so account deletion never blocks on this database.
     * This method retries on queue saturation and logs failures explicitly.
     */
    public CompletableFuture<Result> deletePlayerData(final UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.completedFuture(Result.handled());
        }
        return executeWithRetry("delete friend data for " + uuid, 3, () -> {
            store.deletePlayerData(uuid);
            return null;
        }).thenApply(v -> Result.handled());
    }

    /**
     * Executes a critical database task with retries on queue saturation.
     * Used for operations that must not be silently dropped (e.g., account deletion).
     */
    private <T> CompletableFuture<T> executeWithRetry(final String label, final int maxRetries, final Supplier<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        executeWithRetryInternal(label, maxRetries, task, future, 0);
        return future;
    }

    private <T> void executeWithRetryInternal(final String label, final int maxRetries, final Supplier<T> task,
            final CompletableFuture<T> future, final int attempt) {
        if (closed) {
            future.completeExceptionally(new IllegalStateException("Friends system is shut down"));
            return;
        }
        try {
            databaseExecutor.execute(() -> {
                try {
                    final T result = task.get();
                    sync(() -> future.complete(result));
                } catch (final RuntimeException exception) {
                    warn.accept("Critical task failed (" + label + "): " + exception.getMessage());
                    future.completeExceptionally(exception);
                }
            });
        } catch (final RejectedExecutionException exception) {
            if (attempt < maxRetries) {
                warn.accept("Critical task queue full, retrying (" + (attempt + 1) + "/" + maxRetries + "): " + label);
                try {
                    Thread.sleep(50L * (attempt + 1));
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(ie);
                    return;
                }
                executeWithRetryInternal(label, maxRetries, task, future, attempt + 1);
            } else {
                warn.accept("Critical task permanently rejected after " + maxRetries + " retries: " + label);
                future.completeExceptionally(new IllegalStateException(
                        "Friends database queue saturated; critical cleanup for " + label + " failed"));
            }
        }
    }

    public record Result(boolean success, List<Component> messages) {

        public Result {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        public static Result ok(final String message) {
            return new Result(true, List.of(Component.text(message, NamedTextColor.GREEN)));
        }

        public static Result error(final String message) {
            return new Result(false, List.of(Component.text(message, NamedTextColor.RED)));
        }

        public static Result handled() {
            return new Result(true, List.of());
        }

        public void send(final Player player) {
            if (player == null) {
                return;
            }
            for (final Component message : messages) {
                player.sendMessage(message);
            }
        }
    }

    private record FriendEntry(
            UUID id,
            String name,
            boolean bestFriend,
            String nickname,
            boolean online) {}

    private record ExecutorBundle(Executor executor, ExecutorService owner) {}
}
