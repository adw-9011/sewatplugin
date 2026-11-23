package io.github.near.sweats.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.friends.FriendsStore.AcceptOutcome;
import io.github.near.sweats.friends.FriendsStore.BestFriendOutcome;
import io.github.near.sweats.friends.FriendsStore.DenyOutcome;
import io.github.near.sweats.friends.FriendsStore.NotificationType;
import io.github.near.sweats.friends.FriendsStore.RequestOutcome;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FriendsStoreTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long HOUR = 60L * 60L * 1_000L;

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private FriendsStore store;

    @BeforeEach
    void open() throws Exception {
        store = new FriendsStore(folder.resolve("friends.db"), warnings::add);
        store.open();
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void friendshipsResolveCachedNamesWithoutTheAuthDatabase() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        store.rememberName(alex, "Alex", NOW);
        store.rememberName(bob, "Bob", NOW);

        assertTrue(store.createFriendship(alex, bob, NOW));

        final List<FriendsStore.Friendship> alexFriends = store.getFriendships(alex);
        assertEquals(1, alexFriends.size());
        assertEquals(bob, alexFriends.get(0).friendId());
        assertEquals("Bob", alexFriends.get(0).friendName());

        final List<FriendsStore.Friendship> bobFriends = store.getFriendships(bob);
        assertEquals(1, bobFriends.size());
        assertEquals(alex, bobFriends.get(0).friendId());
        assertEquals("Alex", bobFriends.get(0).friendName());
    }

    @Test
    void requestsBecomeFriendshipsAndQueueAnAcceptanceNotification() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();

        final FriendsStore.RequestResult created = store.createRequest(
                alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, true);
        assertEquals(RequestOutcome.CREATED, created.outcome());

        final List<FriendsStore.FriendRequest> incoming = store.getIncomingRequests(bob);
        assertEquals(1, incoming.size());
        assertEquals(alex, incoming.get(0).requester());

        final FriendsStore.AcceptResult accepted = store.acceptRequest(
                alex, "Alex", bob, "Bob", NOW + 1L, 200);
        assertEquals(AcceptOutcome.ACCEPTED, accepted.outcome());
        assertTrue(accepted.notificationId() > 0L);
        assertTrue(store.areFriends(alex, bob));
        assertTrue(store.getIncomingRequests(bob).isEmpty());

        final List<FriendsStore.FriendNotification> pending = store.getPendingNotifications(alex);
        assertEquals(1, pending.size());
        assertEquals(NotificationType.REQUEST_ACCEPTED, pending.get(0).type());
        assertEquals(bob, pending.get(0).actor());
        assertEquals("Bob", pending.get(0).actorName());
    }

    @Test
    void denyingARequestRemovesItAndQueuesADenialNotification() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        store.createRequest(alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, true);

        final FriendsStore.DenyResult denied = store.denyRequest(
                alex, bob, "Bob", NOW + 1L);
        assertEquals(DenyOutcome.DENIED, denied.outcome());
        assertTrue(denied.notificationId() > 0L);
        assertFalse(store.areFriends(alex, bob));
        assertTrue(store.getIncomingRequests(bob).isEmpty());

        final List<FriendsStore.FriendNotification> pending = store.getPendingNotifications(alex);
        assertEquals(1, pending.size());
        assertEquals(NotificationType.REQUEST_DENIED, pending.get(0).type());
    }

    @Test
    void newPlayersCanHaveRequestsDisabledByDefault() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();

        assertEquals(
                RequestOutcome.TARGET_DISABLED,
                store.createRequest(
                                alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, false)
                        .outcome());
        assertTrue(store.getIncomingRequests(bob).isEmpty());

        assertEquals(
                RequestOutcome.CREATED,
                store.createRequest(
                                alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, true)
                        .outcome());
    }

    @Test
    void reciprocalRequestsCompleteAtomicallyIntoASingleFriendship() throws Exception {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final java.util.concurrent.Future<FriendsStore.RequestResult> first = executor.submit(() -> {
                start.await();
                return store.createRequest(
                        alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, true);
            });
            final java.util.concurrent.Future<FriendsStore.RequestResult> second = executor.submit(() -> {
                start.await();
                return store.createRequest(
                        bob, "Bob", alex, "Alex", NOW, NOW + HOUR, 200, true);
            });
            start.countDown();

            final Set<RequestOutcome> outcomes = Set.of(
                    first.get(5, TimeUnit.SECONDS).outcome(),
                    second.get(5, TimeUnit.SECONDS).outcome());

            assertEquals(
                    Set.of(RequestOutcome.CREATED, RequestOutcome.RECIPROCAL_ACCEPTED),
                    outcomes);
            assertTrue(store.areFriends(alex, bob));
            assertTrue(store.getIncomingRequests(alex).isEmpty());
            assertTrue(store.getIncomingRequests(bob).isEmpty());
            assertEquals(1, store.friendCount(alex));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentAcceptsRespectTheFriendLimit() throws Exception {
        final UUID target = UUID.randomUUID();
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final UUID third = UUID.randomUUID();
        store.createRequest(first, "First", target, "Target", NOW, NOW + HOUR, 10, true);
        store.createRequest(second, "Second", target, "Target", NOW, NOW + HOUR, 10, true);
        store.createRequest(third, "Third", target, "Target", NOW, NOW + HOUR, 10, true);

        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            final List<java.util.concurrent.Future<FriendsStore.AcceptResult>> futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return store.acceptRequest(
                                first, "First", target, "Target", NOW + 1L, 1);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return store.acceptRequest(
                                second, "Second", target, "Target", NOW + 1L, 1);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return store.acceptRequest(
                                third, "Third", target, "Target", NOW + 1L, 1);
                    }));
            start.countDown();

            final Set<AcceptOutcome> outcomes = ConcurrentHashMap.newKeySet();
            for (final java.util.concurrent.Future<FriendsStore.AcceptResult> future : futures) {
                outcomes.add(future.get(5, TimeUnit.SECONDS).outcome());
            }

            assertEquals(1, store.friendCount(target));
            assertEquals(Set.of(AcceptOutcome.ACCEPTED, AcceptOutcome.TARGET_FULL), outcomes);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredRequestsCannotBeAccepted() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        store.createRequest(alex, "Alex", bob, "Bob", NOW, NOW, 200, true);

        final FriendsStore.AcceptResult result = store.acceptRequest(
                alex, "Alex", bob, "Bob", NOW, 200);

        assertEquals(AcceptOutcome.EXPIRED, result.outcome());
        assertFalse(store.areFriends(alex, bob));
        assertTrue(store.getIncomingRequests(bob).isEmpty());
    }

    @Test
    void duplicateAndSelfRequestsAreRejected() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();

        assertEquals(
                RequestOutcome.SELF,
                store.createRequest(alex, "Alex", alex, "Alex", NOW, NOW + HOUR, 200, true)
                        .outcome());
        assertEquals(
                RequestOutcome.CREATED,
                store.createRequest(alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, true)
                        .outcome());
        assertEquals(
                RequestOutcome.ALREADY_REQUESTED,
                store.createRequest(alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, true)
                        .outcome());
        assertEquals(
                RequestOutcome.OUTGOING_PENDING,
                store.createRequest(
                                alex, "Alex", UUID.randomUUID(), "Cat", NOW, NOW + HOUR, 200, true)
                        .outcome());
    }

    @Test
    void friendLimitsApplyToTheRequesterAndTarget() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final UUID cat = UUID.randomUUID();
        store.createFriendship(alex, bob, NOW);

        assertEquals(
                RequestOutcome.REQUESTER_FULL,
                store.createRequest(alex, "Alex", cat, "Cat", NOW, NOW + HOUR, 1, true)
                        .outcome());
        assertEquals(
                RequestOutcome.TARGET_FULL,
                store.createRequest(cat, "Cat", alex, "Alex", NOW, NOW + HOUR, 1, true)
                        .outcome());
    }

    @Test
    void bestFriendsAreLimitedAndRemovedWithTheFriendship() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final UUID cat = UUID.randomUUID();
        store.createFriendship(alex, bob, NOW);
        store.createFriendship(alex, cat, NOW);

        assertEquals(BestFriendOutcome.ADDED,
                store.toggleBestFriend(alex, bob, NOW, 1));
        assertEquals(BestFriendOutcome.LIMIT,
                store.toggleBestFriend(alex, cat, NOW, 1));
        assertEquals(BestFriendOutcome.REMOVED,
                store.toggleBestFriend(alex, bob, NOW, 1));
        assertEquals(BestFriendOutcome.NOT_FRIENDS,
                store.toggleBestFriend(alex, UUID.randomUUID(), NOW, 1));

        assertEquals(BestFriendOutcome.ADDED,
                store.toggleBestFriend(alex, bob, NOW, 1));
        store.setNickname(alex, bob, "Bestie");
        store.setNickname(bob, alex, "Pal");

        assertTrue(store.removeFriendship(alex, bob));
        assertFalse(store.areFriends(alex, bob));
        assertTrue(store.getBestFriends(alex).isEmpty());
        assertTrue(store.getNickname(alex, bob).isEmpty());
        assertTrue(store.getNickname(bob, alex).isEmpty());
    }

    @Test
    void nicknamesRequireAFriendshipAndCanBeRemoved() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();

        assertEquals(
                FriendsStore.NicknameOutcome.NOT_FRIENDS,
                store.setNickname(alex, bob, "Bestie"));
        store.createFriendship(alex, bob, NOW);

        assertEquals(FriendsStore.NicknameOutcome.SET, store.setNickname(alex, bob, "Bestie"));
        assertEquals("Bestie", store.getNickname(alex, bob).orElseThrow());
        assertEquals(
                FriendsStore.NicknameOutcome.REMOVED,
                store.removeNickname(alex, bob));
        assertEquals(
                FriendsStore.NicknameOutcome.NOT_SET,
                store.removeNickname(alex, bob));
        assertTrue(store.getNickname(alex, bob).isEmpty());
    }

    @Test
    void sessionsDeliverOnlyUndeliveredNotificationsAndHonourPreferences() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final UUID cat = UUID.randomUUID();
        store.createRequest(alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, true);
        store.denyRequest(alex, bob, "Bob", NOW + 1L);
        store.createFriendship(alex, bob, NOW);
        store.createFriendship(alex, cat, NOW);
        store.toggleNotifications(cat, true, true);

        final FriendsStore.SessionData first = store.startSession(
                alex, "Alex", NOW + 2L, NOW, true);
        assertEquals(1, first.pendingNotifications().size());
        assertEquals(Set.of(bob), Set.copyOf(first.presenceNotificationTargets()));

        final List<Long> ids = first.pendingNotifications().stream()
                .map(FriendsStore.FriendNotification::id)
                .toList();
        assertEquals(1, store.markNotificationsDelivered(ids, NOW + 3L));
        assertEquals(0, store.markNotificationsDelivered(ids, NOW + 4L));
        assertTrue(store.getPendingNotifications(alex).isEmpty());

        final FriendsStore.SessionData second = store.startSession(
                alex, "Alex", NOW + 10L, NOW + 10L, true);
        assertTrue(second.pendingNotifications().isEmpty());
        assertEquals(Set.of(bob), Set.copyOf(second.presenceNotificationTargets()));
    }

    @Test
    void oldDeliveredNotificationsArePrunedOnSessionStart() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        store.createRequest(alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, true);
        store.denyRequest(alex, bob, "Bob", NOW + 1L);

        final List<FriendsStore.FriendNotification> pending = store.getPendingNotifications(alex);
        assertEquals(1, pending.size());
        final long notificationId = pending.get(0).id();
        assertEquals(1, store.markNotificationsDelivered(List.of(notificationId), NOW + 2L));

        store.startSession(alex, "Alex", NOW + 10L, NOW + 10L, true);

        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + store.file().toAbsolutePath())) {
            try (java.sql.Statement statement = connection.createStatement();
                    java.sql.ResultSet result = statement.executeQuery(
                            "SELECT COUNT(*) FROM friend_notifications")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        } catch (final java.sql.SQLException exception) {
            throw new AssertionError("Could not inspect friends.db", exception);
        }
    }

    @Test
    void deletingAPlayerRemovesEveryReference() {
        final UUID alex = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        store.createRequest(alex, "Alex", bob, "Bob", NOW, NOW + HOUR, 200, true);
        store.denyRequest(alex, bob, "Bob", NOW + 1L);
        store.createFriendship(alex, bob, NOW);
        store.toggleBestFriend(alex, bob, NOW, 10);
        store.setNickname(alex, bob, "Bestie");
        store.setNickname(bob, alex, "Pal");
        store.toggleNotifications(alex, true, true);
        assertFalse(store.getNotificationsEnabled(alex, true));
        assertEquals(1, store.getPendingNotifications(alex).size());

        store.deletePlayerData(alex);

        assertTrue(store.getFriendships(alex).isEmpty());
        assertTrue(store.getFriendships(bob).isEmpty());
        assertTrue(store.getIncomingRequests(alex).isEmpty());
        assertTrue(store.getIncomingRequests(bob).isEmpty());
        assertTrue(store.getBestFriends(alex).isEmpty());
        assertTrue(store.getBestFriends(bob).isEmpty());
        assertTrue(store.getNickname(alex, bob).isEmpty());
        assertTrue(store.getNickname(bob, alex).isEmpty());
        assertTrue(store.getPendingNotifications(alex).isEmpty());
        assertTrue(store.getPendingNotifications(bob).isEmpty());
        assertTrue(store.getNotificationsEnabled(alex, true));
        assertEquals(0, countRows("friend_names", "uuid", alex.toString()));
        assertEquals(0, countRows("friend_notifications", "recipient", alex.toString()));
        assertEquals(0, countRows("friend_preferences", "uuid", alex.toString()));
    }

    private int countRows(final String table, final String column, final String value) {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + store.file().toAbsolutePath());
                java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
            statement.setString(1, value);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        } catch (final java.sql.SQLException exception) {
            throw new AssertionError("Could not inspect friends.db", exception);
        }
    }
}
