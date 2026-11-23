package io.github.near.sweats.friends;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SQLite-backed friend relationships, requests, preferences, nicknames and notifications.
 *
 * <p>All access is serialised on one connection. Every mutation that can affect more than one row
 * runs inside {@code BEGIN IMMEDIATE}; the same lock also prevents a second in-process caller from
 * interleaving statements on this connection. WAL mode keeps external readers from waiting for
 * writes.
 *
 * <p>This database is deliberately separate from {@code auth.db}. It never joins against the auth
 * schema; display names are cached in {@code friend_names} and resolved by UUID everywhere else.
 */
public final class FriendsStore implements AutoCloseable {

    private static final int MAX_STORED_NAME_LENGTH = 64;
    private static final int PENDING_NOTIFICATION_LIMIT = 50;

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS friendships (
                uuid1 TEXT NOT NULL,
                uuid2 TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (uuid1, uuid2)
            );
            CREATE INDEX IF NOT EXISTS idx_friendships_uuid1 ON friendships(uuid1);
            CREATE INDEX IF NOT EXISTS idx_friendships_uuid2 ON friendships(uuid2);

            CREATE TABLE IF NOT EXISTS friend_requests (
                requester TEXT NOT NULL,
                target TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                PRIMARY KEY (requester, target)
            );
            CREATE INDEX IF NOT EXISTS idx_friend_requests_target ON friend_requests(target);
            CREATE INDEX IF NOT EXISTS idx_friend_requests_expires ON friend_requests(expires_at);

            CREATE TABLE IF NOT EXISTS best_friends (
                owner TEXT NOT NULL,
                friend TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (owner, friend)
            );

            CREATE TABLE IF NOT EXISTS friend_nicknames (
                owner TEXT NOT NULL,
                friend TEXT NOT NULL,
                nickname TEXT NOT NULL,
                PRIMARY KEY (owner, friend)
            );

            CREATE TABLE IF NOT EXISTS friend_preferences (
                uuid TEXT PRIMARY KEY,
                notifications_enabled INTEGER NOT NULL DEFAULT 1,
                requests_enabled INTEGER NOT NULL DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS friend_names (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS friend_notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recipient TEXT NOT NULL,
                type TEXT NOT NULL,
                actor TEXT NOT NULL,
                actor_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                delivered_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS idx_friend_notifications_pending
                ON friend_notifications(recipient, delivered_at, created_at);
            CREATE INDEX IF NOT EXISTS idx_friend_notifications_delivered
                ON friend_notifications(delivered_at);

            CREATE TABLE IF NOT EXISTS friend_cooldowns (
                uuid TEXT PRIMARY KEY,
                expires_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_friend_cooldowns_expires ON friend_cooldowns(expires_at);
            """;

    private final Path file;
    private final Consumer<String> warn;
    private final Object lock = new Object();

    private Connection connection;

    public FriendsStore(final Path file, final Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
    }

    public Path file() {
        return file;
    }

    public void open() throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (final IOException exception) {
            throw new SQLException("Could not create the friends database directory", exception);
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (final ClassNotFoundException exception) {
            throw new SQLException("The SQLite JDBC driver is not available", exception);
        }

        synchronized (lock) {
            if (connection != null) {
                return;
            }
            final Connection opened =
                    DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try {
                try (Statement statement = opened.createStatement()) {
                    statement.execute("PRAGMA journal_mode=WAL");
                    statement.execute("PRAGMA busy_timeout=5000");
                    for (final String sql : SCHEMA.split(";")) {
                        if (!sql.isBlank()) {
                            statement.execute(sql);
                        }
                    }
                }
                connection = opened;
            } catch (final SQLException exception) {
                try {
                    opened.close();
                } catch (final SQLException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        }
    }

    public boolean isOpen() {
        synchronized (lock) {
            return connection != null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (final SQLException exception) {
                warn.accept("Could not close friends.db: " + exception.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Friendships (mutual)

    /**
     * Creates a mutual friendship between two players.
     *
     * @return true when the friendship was created, false if it already existed
     */
    public boolean createFriendship(final UUID uuid1, final UUID uuid2, final long now) {
        if (uuid1.equals(uuid2)) {
            return false;
        }
        return write("create friendship", () -> insertFriendship(uuid1, uuid2, now));
    }

    /**
     * Removes a mutual friendship and every per-player decoration tied to it.
     *
     * @return true when the friendship existed and was removed
     */
    public boolean removeFriendship(final UUID uuid1, final UUID uuid2) {
        if (uuid1.equals(uuid2)) {
            return false;
        }
        return write("remove friendship", () -> {
            final boolean removed = deleteFriendship(uuid1, uuid2);
            deleteBestFriendRows(uuid1, uuid2);
            deleteNicknameRows(uuid1, uuid2);
            return removed;
        });
    }

    /**
     * Checks if two players are friends.
     */
    public boolean areFriends(final UUID uuid1, final UUID uuid2) {
        if (uuid1.equals(uuid2)) {
            return false;
        }
        return read("check friendship", () -> friendshipExists(uuid1, uuid2));
    }

    /**
     * Returns all friends of a player, including their cached name, best-friend flag and the
     * viewer-specific nickname. This uses a fixed number of queries rather than one per friend.
     */
    public List<Friendship> getFriendships(final UUID uuid) {
        return read("load friendships", () -> {
            final List<Friendship> friendships = new ArrayList<>();
            final List<RawFriendship> raw = new ArrayList<>();

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT f.uuid1, f.uuid2, f.created_at, n1.name, n2.name "
                            + "FROM friendships f "
                            + "LEFT JOIN friend_names n1 ON n1.uuid = f.uuid1 "
                            + "LEFT JOIN friend_names n2 ON n2.uuid = f.uuid2 "
                            + "WHERE f.uuid1 = ? OR f.uuid2 = ? "
                            + "ORDER BY f.created_at DESC")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final UUID uuid1 = parseUuid(result.getString(1));
                        final UUID uuid2 = parseUuid(result.getString(2));
                        if (uuid1 == null || uuid2 == null) {
                            continue;
                        }
                        final boolean first = uuid1.equals(uuid);
                        final UUID friendId = first ? uuid2 : uuid1;
                        final String friendName = first ? result.getString(5) : result.getString(4);
                        raw.add(new RawFriendship(friendId, friendName, result.getLong(3)));
                    }
                }
            }

            final Set<UUID> bestFriends = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT friend FROM best_friends WHERE owner = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final UUID id = parseUuid(result.getString(1));
                        if (id != null) {
                            bestFriends.add(id);
                        }
                    }
                }
            }

            final java.util.Map<UUID, String> nicknames = new java.util.HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT friend, nickname FROM friend_nicknames WHERE owner = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final UUID id = parseUuid(result.getString(1));
                        if (id != null) {
                            nicknames.put(id, result.getString(2));
                        }
                    }
                }
            }

            for (final RawFriendship entry : raw) {
                friendships.add(new Friendship(
                        entry.friendId(),
                        entry.friendName(),
                        entry.createdAt(),
                        bestFriends.contains(entry.friendId()),
                        nicknames.get(entry.friendId())));
            }
            friendships.sort((left, right) -> Long.compare(right.createdAt(), left.createdAt()));
            return friendships;
        });
    }

    /**
     * Returns the friend count for a player.
     */
    public int friendCount(final UUID uuid) {
        return read("count friendships", () -> countFriendships(uuid));
    }

    public record Friendship(
            UUID friendId,
            String friendName,
            long createdAt,
            boolean bestFriend,
            String nickname) {}

    private record RawFriendship(UUID friendId, String friendName, long createdAt) {}

    // ------------------------------------------------------------------------------------------
    // Friend Requests

    /**
     * Creates a friend request, or completes a reciprocal request atomically.
     *
     * <p>The friend limit, duplicate checks and reciprocal handling all happen under the same
     * immediate transaction. A simultaneous A-to-B and B-to-A pair therefore leaves exactly one
     * friendship and no pending request.
     *
     * @param defaultRequestsEnabled the default incoming-request preference for players who have
     * never toggled the setting; must be supplied explicitly by the caller
     */
    public RequestResult createRequest(
            final UUID requester,
            final String requesterName,
            final UUID target,
            final String targetName,
            final long now,
            final long expiresAt,
            final int maxFriends,
            final boolean defaultRequestsEnabled) {
        if (requester.equals(target)) {
            return new RequestResult(RequestOutcome.SELF, 0L, 0L);
        }
        return write("save friend request", () -> {
            rememberNameInternal(requester, requesterName, now);
            rememberNameInternal(target, targetName, now);
            deleteExpiredRequests(now);

            if (friendshipExists(requester, target)) {
                return new RequestResult(RequestOutcome.ALREADY_FRIENDS, 0L, 0L);
            }

            final Optional<StoredRequest> incoming = findRequest(target, requester, now);
            if (incoming.isPresent()) {
                if (atFriendLimit(requester, maxFriends)) {
                    return new RequestResult(RequestOutcome.REQUESTER_FULL, 0L, 0L);
                }
                if (atFriendLimit(target, maxFriends)) {
                    return new RequestResult(RequestOutcome.TARGET_FULL, 0L, 0L);
                }
                deleteRequest(target, requester);
                insertFriendship(requester, target, now);
                return new RequestResult(RequestOutcome.RECIPROCAL_ACCEPTED, now, 0L);
            }

            final Optional<StoredRequest> outgoing = findOutgoing(requester, now);
            if (outgoing.isPresent()) {
                return new RequestResult(
                        outgoing.get().target().equals(target)
                                ? RequestOutcome.ALREADY_REQUESTED
                                : RequestOutcome.OUTGOING_PENDING,
                        0L,
                        0L);
            }

            if (!requestsEnabled(target, defaultRequestsEnabled)) {
                return new RequestResult(RequestOutcome.TARGET_DISABLED, 0L, 0L);
            }
            if (atFriendLimit(requester, maxFriends)) {
                return new RequestResult(RequestOutcome.REQUESTER_FULL, 0L, 0L);
            }
            if (atFriendLimit(target, maxFriends)) {
                return new RequestResult(RequestOutcome.TARGET_FULL, 0L, 0L);
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO friend_requests (requester, target, created_at, expires_at) "
                            + "VALUES (?, ?, ?, ?)")) {
                statement.setString(1, requester.toString());
                statement.setString(2, target.toString());
                statement.setLong(3, now);
                statement.setLong(4, expiresAt);
                statement.executeUpdate();
            }
            return new RequestResult(RequestOutcome.CREATED, now, expiresAt);
        });
    }

    /**
     * Accepts a request and creates the friendship in one transaction. The notification row is
     * written in the same transaction so the requester cannot lose an offline acceptance.
     */
    public AcceptResult acceptRequest(
            final UUID requester,
            final String requesterName,
            final UUID target,
            final String targetName,
            final long now,
            final int maxFriends) {
        if (requester.equals(target)) {
            return new AcceptResult(AcceptOutcome.NO_REQUEST, 0L);
        }
        return write("accept friend request", () -> {
            rememberNameInternal(requester, requesterName, now);
            rememberNameInternal(target, targetName, now);

            if (friendshipExists(requester, target)) {
                deleteRequest(requester, target);
                return new AcceptResult(AcceptOutcome.ALREADY_FRIENDS, 0L);
            }

            final Optional<StoredRequest> request = findAnyRequest(requester, target);
            if (request.isEmpty()) {
                return new AcceptResult(AcceptOutcome.NO_REQUEST, 0L);
            }
            if (request.get().expiresAt() <= now) {
                deleteRequest(requester, target);
                return new AcceptResult(AcceptOutcome.EXPIRED, 0L);
            }
            if (atFriendLimit(requester, maxFriends)) {
                return new AcceptResult(AcceptOutcome.REQUESTER_FULL, 0L);
            }
            if (atFriendLimit(target, maxFriends)) {
                return new AcceptResult(AcceptOutcome.TARGET_FULL, 0L);
            }

            if (!insertFriendship(requester, target, now)) {
                deleteRequest(requester, target);
                return new AcceptResult(AcceptOutcome.ALREADY_FRIENDS, 0L);
            }
            deleteRequest(requester, target);
            final long notificationId = insertNotification(
                    requester,
                    NotificationType.REQUEST_ACCEPTED,
                    target,
                    targetName,
                    now);
            return new AcceptResult(AcceptOutcome.ACCEPTED, notificationId);
        });
    }

    /**
     * Denies an active request and queues the requester's notification atomically.
     */
    public DenyResult denyRequest(
            final UUID requester,
            final UUID target,
            final String targetName,
            final long now) {
        if (requester.equals(target)) {
            return new DenyResult(DenyOutcome.NO_REQUEST, 0L);
        }
        return write("deny friend request", () -> {
            final Optional<StoredRequest> request = findAnyRequest(requester, target);
            if (request.isEmpty()) {
                return new DenyResult(DenyOutcome.NO_REQUEST, 0L);
            }
            if (request.get().expiresAt() <= now) {
                deleteRequest(requester, target);
                return new DenyResult(DenyOutcome.EXPIRED, 0L);
            }
            deleteRequest(requester, target);
            final long notificationId = insertNotification(
                    requester,
                    NotificationType.REQUEST_DENIED,
                    target,
                    targetName,
                    now);
            return new DenyResult(DenyOutcome.DENIED, notificationId);
        });
    }

    /**
     * Cancels an outgoing request without notifying either player.
     */
    public boolean cancelRequest(final UUID requester, final UUID target) {
        return write("cancel friend request", () -> deleteRequest(requester, target));
    }

    /**
     * Returns all active incoming friend requests for a player.
     */
    public List<FriendRequest> getIncomingRequests(final UUID target) {
        final long now = System.currentTimeMillis();
        return read("load incoming requests", () -> {
            final List<FriendRequest> requests = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT requester, target, created_at, expires_at FROM friend_requests "
                            + "WHERE target = ? AND expires_at > ? "
                            + "ORDER BY created_at DESC")) {
                statement.setString(1, target.toString());
                statement.setLong(2, now);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final FriendRequest request = readRequest(result);
                        if (request != null) {
                            requests.add(request);
                        }
                    }
                }
            }
            return requests;
        });
    }

    public record FriendRequest(UUID requester, UUID target, long createdAt, long expiresAt) {}

    public enum RequestOutcome {
        CREATED,
        RECIPROCAL_ACCEPTED,
        SELF,
        ALREADY_FRIENDS,
        ALREADY_REQUESTED,
        OUTGOING_PENDING,
        REQUESTER_FULL,
        TARGET_FULL,
        TARGET_DISABLED
    }

    public record RequestResult(RequestOutcome outcome, long createdAt, long expiresAt) {}

    public enum AcceptOutcome {
        ACCEPTED,
        NO_REQUEST,
        EXPIRED,
        ALREADY_FRIENDS,
        REQUESTER_FULL,
        TARGET_FULL
    }

    public record AcceptResult(AcceptOutcome outcome, long notificationId) {}

    public enum DenyOutcome {
        DENIED,
        NO_REQUEST,
        EXPIRED
    }

    public record DenyResult(DenyOutcome outcome, long notificationId) {}

    // ------------------------------------------------------------------------------------------
    // Best Friends

    public BestFriendOutcome toggleBestFriend(
            final UUID owner, final UUID friend, final long now, final int maxBestFriends) {
        if (owner.equals(friend)) {
            return BestFriendOutcome.NOT_FRIENDS;
        }
        return write("toggle best friend", () -> {
            if (!friendshipExists(owner, friend)) {
                return BestFriendOutcome.NOT_FRIENDS;
            }
            if (isBestFriend(owner, friend)) {
                removeBestFriend(owner, friend);
                return BestFriendOutcome.REMOVED;
            }
            if (maxBestFriends > 0 && countBestFriends(owner) >= maxBestFriends) {
                return BestFriendOutcome.LIMIT;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO best_friends (owner, friend, created_at) VALUES (?, ?, ?)")) {
                statement.setString(1, owner.toString());
                statement.setString(2, friend.toString());
                statement.setLong(3, now);
                statement.executeUpdate();
            }
            return BestFriendOutcome.ADDED;
        });
    }

    public boolean isBestFriend(final UUID owner, final UUID friend) {
        if (owner.equals(friend)) {
            return false;
        }
        return read("check best friend", () -> isBestFriendInternal(owner, friend));
    }

    public List<UUID> getBestFriends(final UUID owner) {
        return read("load best friends", () -> {
            final List<UUID> bestFriends = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT friend FROM best_friends WHERE owner = ?")) {
                statement.setString(1, owner.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final UUID id = parseUuid(result.getString(1));
                        if (id != null) {
                            bestFriends.add(id);
                        }
                    }
                }
            }
            return bestFriends;
        });
    }

    public enum BestFriendOutcome {
        ADDED,
        REMOVED,
        NOT_FRIENDS,
        LIMIT
    }

    // ------------------------------------------------------------------------------------------
    // Friend Nicknames (owner-specific)

    public NicknameOutcome setNickname(final UUID owner, final UUID friend, final String nickname) {
        if (owner.equals(friend)) {
            return NicknameOutcome.NOT_FRIENDS;
        }
        return write("save friend nickname", () -> {
            if (!friendshipExists(owner, friend)) {
                return NicknameOutcome.NOT_FRIENDS;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO friend_nicknames (owner, friend, nickname) VALUES (?, ?, ?) "
                            + "ON CONFLICT(owner, friend) DO UPDATE SET nickname = excluded.nickname")) {
                statement.setString(1, owner.toString());
                statement.setString(2, friend.toString());
                statement.setString(3, nickname);
                statement.executeUpdate();
            }
            return NicknameOutcome.SET;
        });
    }

    public Optional<String> getNickname(final UUID owner, final UUID friend) {
        if (owner.equals(friend)) {
            return Optional.empty();
        }
        return read("load friend nickname", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT nickname FROM friend_nicknames WHERE owner = ? AND friend = ?")) {
                statement.setString(1, owner.toString());
                statement.setString(2, friend.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.ofNullable(result.getString(1))
                            : Optional.empty();
                }
            }
        });
    }

    public NicknameOutcome removeNickname(final UUID owner, final UUID friend) {
        if (owner.equals(friend)) {
            return NicknameOutcome.NOT_FRIENDS;
        }
        return write("remove friend nickname", () -> {
            if (!friendshipExists(owner, friend)) {
                return NicknameOutcome.NOT_FRIENDS;
            }
            final boolean removed = deleteNickname(owner, friend);
            return removed ? NicknameOutcome.REMOVED : NicknameOutcome.NOT_SET;
        });
    }

    public enum NicknameOutcome {
        SET,
        REMOVED,
        NOT_SET,
        NOT_FRIENDS
    }

    // ------------------------------------------------------------------------------------------
    // Preferences

    public boolean getNotificationsEnabled(final UUID uuid, final boolean fallback) {
        return read("load notifications preference",
                () -> preference(uuid, "notifications_enabled", fallback));
    }

    public boolean getRequestsEnabled(final UUID uuid, final boolean fallback) {
        return read("load requests preference",
                () -> preference(uuid, "requests_enabled", fallback));
    }

    public boolean toggleNotifications(
            final UUID uuid, final boolean fallbackNotifications, final boolean fallbackRequests) {
        return write("toggle notifications preference", () -> {
            final PreferenceRow current = preferenceRow(uuid, fallbackNotifications, fallbackRequests);
            final boolean enabled = !current.notificationsEnabled();
            savePreferences(uuid, enabled, current.requestsEnabled());
            return enabled;
        });
    }

    public boolean toggleRequests(
            final UUID uuid, final boolean fallbackNotifications, final boolean fallbackRequests) {
        return write("toggle requests preference", () -> {
            final PreferenceRow current = preferenceRow(uuid, fallbackNotifications, fallbackRequests);
            final boolean enabled = !current.requestsEnabled();
            savePreferences(uuid, current.notificationsEnabled(), enabled);
            return enabled;
        });
    }

    /**
     * Returns the friends that should receive this player's join/leave messages. The preference
     * lookup is joined in one query, so a player with hundreds of friends does not cause one
     * query per friend.
     */
    public List<UUID> getPresenceNotificationTargets(
            final UUID uuid, final boolean defaultNotifications) {
        return read("load presence notification targets", () -> {
            final List<UUID> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT CASE WHEN f.uuid1 = ? THEN f.uuid2 ELSE f.uuid1 END AS friend, "
                            + "COALESCE(p.notifications_enabled, ?) AS enabled "
                            + "FROM friendships f "
                            + "LEFT JOIN friend_preferences p "
                            + "ON p.uuid = CASE WHEN f.uuid1 = ? THEN f.uuid2 ELSE f.uuid1 END "
                            + "WHERE f.uuid1 = ? OR f.uuid2 = ?")) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, defaultNotifications ? 1 : 0);
                statement.setString(3, uuid.toString());
                statement.setString(4, uuid.toString());
                statement.setString(5, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if (result.getInt(2) == 0) {
                            continue;
                        }
                        final UUID id = parseUuid(result.getString(1));
                        if (id != null && !id.equals(uuid)) {
                            ids.add(id);
                        }
                    }
                }
            }
            return ids;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Offline notifications

    public SessionData startSession(
            final UUID uuid,
            final String playerName,
            final long now,
            final long deliveredRetentionCutoff,
            final boolean defaultNotifications) {
        return write("start friend session", () -> {
            rememberNameInternal(uuid, playerName, now);
            deleteExpiredRequests(now);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_notifications "
                            + "WHERE delivered_at IS NOT NULL AND delivered_at < ?")) {
                statement.setLong(1, deliveredRetentionCutoff);
                statement.executeUpdate();
            }

            final List<FriendNotification> pending = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, type, actor, actor_name, created_at "
                            + "FROM friend_notifications "
                            + "WHERE recipient = ? AND delivered_at IS NULL "
                            + "ORDER BY created_at ASC, id ASC LIMIT ?")) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, PENDING_NOTIFICATION_LIMIT);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final UUID actor = parseUuid(result.getString(3));
                        final NotificationType type = NotificationType.parse(result.getString(2));
                        if (actor == null || type == null) {
                            continue;
                        }
                        pending.add(new FriendNotification(
                                result.getLong(1),
                                type,
                                actor,
                                result.getString(4),
                                result.getLong(5)));
                    }
                }
            }

            final List<UUID> presenceTargets = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT CASE WHEN f.uuid1 = ? THEN f.uuid2 ELSE f.uuid1 END AS friend, "
                            + "COALESCE(p.notifications_enabled, ?) AS enabled "
                            + "FROM friendships f "
                            + "LEFT JOIN friend_preferences p "
                            + "ON p.uuid = CASE WHEN f.uuid1 = ? THEN f.uuid2 ELSE f.uuid1 END "
                            + "WHERE f.uuid1 = ? OR f.uuid2 = ?")) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, defaultNotifications ? 1 : 0);
                statement.setString(3, uuid.toString());
                statement.setString(4, uuid.toString());
                statement.setString(5, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final UUID id = parseUuid(result.getString(1));
                        if (id != null && result.getInt(2) != 0 && !id.equals(uuid)) {
                            presenceTargets.add(id);
                        }
                    }
                }
            }

            return new SessionData(pending, presenceTargets);
        });
    }

    public int markNotificationsDelivered(final List<Long> notificationIds, final long now) {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return 0;
        }
        return write("mark friend notifications delivered", () -> {
            final StringBuilder sql = new StringBuilder(
                    "UPDATE friend_notifications SET delivered_at = ? "
                            + "WHERE delivered_at IS NULL AND id IN (");
            for (int index = 0; index < notificationIds.size(); index++) {
                if (index > 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            sql.append(')');
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                statement.setLong(1, now);
                for (int index = 0; index < notificationIds.size(); index++) {
                    statement.setLong(index + 2, notificationIds.get(index));
                }
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Prunes undelivered notifications older than the retention cutoff.
     * Returns the number of pruned notifications.
     */
    public int pruneUndeliveredNotifications(final long retentionCutoff) {
        return write("prune undelivered notifications", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_notifications WHERE delivered_at IS NULL AND created_at < ?")) {
                statement.setLong(1, retentionCutoff);
                return statement.executeUpdate();
            }
        });
    }

    public List<FriendNotification> getPendingNotifications(final UUID recipient) {
        return read("load pending friend notifications", () -> {
            final List<FriendNotification> pending = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, type, actor, actor_name, created_at "
                            + "FROM friend_notifications "
                            + "WHERE recipient = ? AND delivered_at IS NULL "
                            + "ORDER BY created_at ASC, id ASC LIMIT ?")) {
                statement.setString(1, recipient.toString());
                statement.setInt(2, PENDING_NOTIFICATION_LIMIT);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final UUID actor = parseUuid(result.getString(3));
                        final NotificationType type = NotificationType.parse(result.getString(2));
                        if (actor == null || type == null) {
                            continue;
                        }
                        pending.add(new FriendNotification(
                                result.getLong(1),
                                type,
                                actor,
                                result.getString(4),
                                result.getLong(5)));
                    }
                }
            }
            return pending;
        });
    }

    public record SessionData(
            List<FriendNotification> pendingNotifications,
            List<UUID> presenceNotificationTargets) {}

    public record FriendNotification(
            long id, NotificationType type, UUID actor, String actorName, long createdAt) {}

    public enum NotificationType {
        REQUEST_ACCEPTED,
        REQUEST_DENIED;

        static NotificationType parse(final String value) {
            if (value == null) {
                return null;
            }
            try {
                return valueOf(value);
            } catch (final IllegalArgumentException exception) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Names and deletion

    /** Caches the latest account name for UUID-based display, including offline friends. */
    public void rememberName(final UUID uuid, final String name, final long now) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        write("remember player name", () -> {
            rememberNameInternal(uuid, name, now);
            return null;
        });
    }

    /** Deletes every Friends row that references an account, transactionally. */
    public void deletePlayerData(final UUID uuid) {
        write("delete player friend data", () -> {
            final String id = uuid.toString();
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friendships WHERE uuid1 = ? OR uuid2 = ?")) {
                statement.setString(1, id);
                statement.setString(2, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_requests WHERE requester = ? OR target = ?")) {
                statement.setString(1, id);
                statement.setString(2, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM best_friends WHERE owner = ? OR friend = ?")) {
                statement.setString(1, id);
                statement.setString(2, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_nicknames WHERE owner = ? OR friend = ?")) {
                statement.setString(1, id);
                statement.setString(2, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_preferences WHERE uuid = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_notifications WHERE recipient = ? OR actor = ?")) {
                statement.setString(1, id);
                statement.setString(2, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_names WHERE uuid = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Internal SQL helpers (always called while holding {@link #lock})

    private record PreferenceRow(boolean notificationsEnabled, boolean requestsEnabled) {}

    private record StoredRequest(UUID requester, UUID target, long createdAt, long expiresAt) {}

    private void rememberNameInternal(final UUID uuid, final String name, final long now)
            throws SQLException {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        String safe = name.trim();
        if (safe.length() > MAX_STORED_NAME_LENGTH) {
            safe = safe.substring(0, MAX_STORED_NAME_LENGTH);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO friend_names (uuid, name, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, "
                        + "updated_at = excluded.updated_at")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, safe);
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }

    private boolean friendshipExists(final UUID uuid1, final UUID uuid2) throws SQLException {
        final UUID first = first(uuid1, uuid2);
        final UUID second = second(uuid1, uuid2);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM friendships WHERE uuid1 = ? AND uuid2 = ?")) {
            statement.setString(1, first.toString());
            statement.setString(2, second.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean insertFriendship(final UUID uuid1, final UUID uuid2, final long now)
            throws SQLException {
        final UUID first = first(uuid1, uuid2);
        final UUID second = second(uuid1, uuid2);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO friendships (uuid1, uuid2, created_at) VALUES (?, ?, ?)")) {
            statement.setString(1, first.toString());
            statement.setString(2, second.toString());
            statement.setLong(3, now);
            return statement.executeUpdate() > 0;
        }
    }

    private boolean deleteFriendship(final UUID uuid1, final UUID uuid2) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM friendships WHERE uuid1 = ? AND uuid2 = ?")) {
            statement.setString(1, first(uuid1, uuid2).toString());
            statement.setString(2, second(uuid1, uuid2).toString());
            return statement.executeUpdate() > 0;
        }
    }

    private int countFriendships(final UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM friendships WHERE uuid1 = ? OR uuid2 = ?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private boolean atFriendLimit(final UUID uuid, final int maxFriends) throws SQLException {
        return maxFriends > 0 && countFriendships(uuid) >= maxFriends;
    }

    private Optional<StoredRequest> findRequest(
            final UUID requester, final UUID target, final long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT requester, target, created_at, expires_at FROM friend_requests "
                        + "WHERE requester = ? AND target = ? AND expires_at > ?")) {
            statement.setString(1, requester.toString());
            statement.setString(2, target.toString());
            statement.setLong(3, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readStoredRequest(result)) : Optional.empty();
            }
        }
    }

    private Optional<StoredRequest> findAnyRequest(final UUID requester, final UUID target)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT requester, target, created_at, expires_at FROM friend_requests "
                        + "WHERE requester = ? AND target = ?")) {
            statement.setString(1, requester.toString());
            statement.setString(2, target.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readStoredRequest(result)) : Optional.empty();
            }
        }
    }

    private Optional<StoredRequest> findOutgoing(final UUID requester, final long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT requester, target, created_at, expires_at FROM friend_requests "
                        + "WHERE requester = ? AND expires_at > ? LIMIT 1")) {
            statement.setString(1, requester.toString());
            statement.setLong(2, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readStoredRequest(result)) : Optional.empty();
            }
        }
    }

    private static StoredRequest readStoredRequest(final ResultSet result) throws SQLException {
        final UUID requester = parseUuid(result.getString(1));
        final UUID target = parseUuid(result.getString(2));
        if (requester == null || target == null) {
            throw new SQLException("Friend request contains an invalid UUID");
        }
        return new StoredRequest(
                requester, target, result.getLong(3), result.getLong(4));
    }

    private static FriendRequest toFriendRequest(final StoredRequest request) {
        return new FriendRequest(
                request.requester(), request.target(), request.createdAt(), request.expiresAt());
    }

    private static FriendRequest readRequest(final ResultSet result) throws SQLException {
        final StoredRequest request = readStoredRequest(result);
        return toFriendRequest(request);
    }

    private boolean deleteRequest(final UUID requester, final UUID target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM friend_requests WHERE requester = ? AND target = ?")) {
            statement.setString(1, requester.toString());
            statement.setString(2, target.toString());
            return statement.executeUpdate() > 0;
        }
    }

    private void deleteExpiredRequests(final long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM friend_requests WHERE expires_at <= ?")) {
            statement.setLong(1, now);
            statement.executeUpdate();
        }
    }

    private boolean requestsEnabled(final UUID uuid, final boolean fallback) throws SQLException {
        return preference(uuid, "requests_enabled", fallback);
    }

    private boolean preference(final UUID uuid, final String column, final boolean fallback)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM friend_preferences WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) != 0 : fallback;
            }
        }
    }

    private PreferenceRow preferenceRow(
            final UUID uuid,
            final boolean fallbackNotifications,
            final boolean fallbackRequests) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT notifications_enabled, requests_enabled "
                        + "FROM friend_preferences WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new PreferenceRow(result.getInt(1) != 0, result.getInt(2) != 0);
                }
                return new PreferenceRow(fallbackNotifications, fallbackRequests);
            }
        }
    }

    private void savePreferences(
            final UUID uuid, final boolean notifications, final boolean requests)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO friend_preferences (uuid, notifications_enabled, requests_enabled) "
                        + "VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET "
                        + "notifications_enabled = excluded.notifications_enabled, "
                        + "requests_enabled = excluded.requests_enabled")) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, notifications ? 1 : 0);
            statement.setInt(3, requests ? 1 : 0);
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Request cooldowns (persistent)

    /**
     * Sets a request cooldown for a player, persisting it to the database.
     */
    public void setRequestCooldown(final UUID uuid, final long expiresAt) {
        write("set request cooldown", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO friend_cooldowns (uuid, expires_at) VALUES (?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET expires_at = excluded.expires_at")) {
                statement.setString(1, uuid.toString());
                statement.setLong(2, expiresAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Gets the remaining cooldown time in milliseconds, or 0 if no cooldown is active.
     * Expired cooldowns are automatically removed.
     */
    public long getRequestCooldownRemaining(final UUID uuid, final long now) {
        return read("get request cooldown", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT expires_at FROM friend_cooldowns WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        final long expiresAt = result.getLong(1);
                        if (expiresAt <= now) {
                            deleteRequestCooldown(uuid);
                            return 0L;
                        }
                        return expiresAt - now;
                    }
                    return 0L;
                }
            }
        });
    }

    /**
     * Removes a player's request cooldown.
     */
    public void deleteRequestCooldown(final UUID uuid) {
        write("delete request cooldown", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_cooldowns WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Prunes all expired cooldowns from the database.
     */
    public void pruneExpiredCooldowns(final long now) {
        write("prune expired cooldowns", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_cooldowns WHERE expires_at <= ?")) {
                statement.setLong(1, now);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private boolean isBestFriendInternal(final UUID owner, final UUID friend) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM best_friends WHERE owner = ? AND friend = ?")) {
            statement.setString(1, owner.toString());
            statement.setString(2, friend.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private int countBestFriends(final UUID owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM best_friends WHERE owner = ?")) {
            statement.setString(1, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private void removeBestFriend(final UUID owner, final UUID friend) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM best_friends WHERE owner = ? AND friend = ?")) {
            statement.setString(1, owner.toString());
            statement.setString(2, friend.toString());
            statement.executeUpdate();
        }
    }

    private void deleteBestFriendRows(final UUID uuid1, final UUID uuid2) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM best_friends "
                        + "WHERE (owner = ? AND friend = ?) OR (owner = ? AND friend = ?)")) {
            statement.setString(1, uuid1.toString());
            statement.setString(2, uuid2.toString());
            statement.setString(3, uuid2.toString());
            statement.setString(4, uuid1.toString());
            statement.executeUpdate();
        }
    }

    private boolean deleteNickname(final UUID owner, final UUID friend) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM friend_nicknames WHERE owner = ? AND friend = ?")) {
            statement.setString(1, owner.toString());
            statement.setString(2, friend.toString());
            return statement.executeUpdate() > 0;
        }
    }

    private void deleteNicknameRows(final UUID uuid1, final UUID uuid2) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM friend_nicknames "
                        + "WHERE (owner = ? AND friend = ?) OR (owner = ? AND friend = ?)")) {
            statement.setString(1, uuid1.toString());
            statement.setString(2, uuid2.toString());
            statement.setString(3, uuid2.toString());
            statement.setString(4, uuid1.toString());
            statement.executeUpdate();
        }
    }

    private long insertNotification(
            final UUID recipient,
            final NotificationType type,
            final UUID actor,
            final String actorName,
            final long now) throws SQLException {
        final String safeName = actorName == null || actorName.isBlank()
                ? actor.toString()
                : actorName.trim();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO friend_notifications "
                        + "(recipient, type, actor, actor_name, created_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, recipient.toString());
            statement.setString(2, type.name());
            statement.setString(3, actor.toString());
            statement.setString(4, safeName);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement();
                ResultSet keys = statement.executeQuery("SELECT last_insert_rowid()")) {
            return keys.next() ? keys.getLong(1) : 0L;
        }
    }

    private static UUID first(final UUID left, final UUID right) {
        return left.compareTo(right) < 0 ? left : right;
    }

    private static UUID second(final UUID left, final UUID right) {
        return left.compareTo(right) < 0 ? right : left;
    }

    private static UUID parseUuid(final String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Transaction and connection helpers

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private <T> T read(final String action, final SqlWork<T> work) {
        synchronized (lock) {
            requireOpen(action);
            try {
                return work.run();
            } catch (final SQLException exception) {
                throw failure(action, exception);
            }
        }
    }

    private <T> T write(final String action, final SqlWork<T> work) {
        synchronized (lock) {
            requireOpen(action);
            beginImmediate(action);
            boolean committed = false;
            try {
                final T result = work.run();
                commit(action);
                committed = true;
                return result;
            } catch (final SQLException exception) {
                if (!committed) {
                    rollback();
                }
                throw failure(action, exception);
            } catch (final RuntimeException exception) {
                if (!committed) {
                    rollback();
                }
                throw exception;
            }
        }
    }

    private void requireOpen(final String action) {
        if (connection == null) {
            throw new FriendsStoreException("Friends database is unavailable (" + action + ").");
        }
    }

    private void beginImmediate(final String action) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
        } catch (final SQLException exception) {
            throw failure("start " + action, exception);
        }
    }

    private void commit(final String action) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("COMMIT");
        } catch (final SQLException exception) {
            throw failure("commit " + action, exception);
        }
    }

    private void rollback() {
        if (connection == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (final SQLException exception) {
            warn.accept("Could not roll back a friends transaction: " + exception.getMessage());
        }
    }

    private FriendsStoreException failure(final String action, final SQLException exception) {
        warn.accept("Could not " + action + " in friends.db: " + exception.getMessage());
        return new FriendsStoreException("Could not " + action + " in friends.db", exception);
    }

    /** Raised when a Friends query cannot be completed; callers map this to a safe user message. */
    public static final class FriendsStoreException extends RuntimeException {
        public FriendsStoreException(final String message) {
            super(message);
        }

        public FriendsStoreException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
