package io.github.near.sweats.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FriendsServiceTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long HOUR = 60L * 60L * 1_000L;

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private final Map<UUID, Player> online = new HashMap<>();
    private final Map<UUID, List<Component>> inboxes = new HashMap<>();
    private final AtomicLong now = new AtomicLong(NOW);

    private YamlConfiguration config;
    private RankService ranks;
    private PermissionService permissions;
    private FriendsStore store;
    private FriendsService service;

    @BeforeEach
    void setUp() throws Exception {
        config = new YamlConfiguration();
        config.set("friends.request-expire-hours", 72);
        config.set("friends.max-friends", 200);
        config.set("friends.max-best-friends", 10);
        config.set("friends.list-page-size", 10);
        config.set("friends.request-cooldown-seconds", 3);
        config.set("friends.notification-retention-days", 30);
        config.set("friends.default-notifications-enabled", true);
        config.set("friends.default-requests-enabled", true);
        config.set("friends.max-nickname-length", 24);

        ranks = new RankService(folder, warnings::add);
        permissions = new PermissionService(
                TestPlugins.plugin(config), folder, ranks, warnings::add);
        store = new FriendsStore(folder.resolve("friends.db"), warnings::add);
        store.open();
        service = service(Runnable::run);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        if (ranks != null) {
            ranks.close();
        }
    }

    @Test
    void requestsAndReciprocalRequestsCreateExactlyOneFriendship() throws Exception {
        final Player alex = player("Alex", true);
        final Player bob = player("Bob", true);

        final FriendsService.Result sent = service.sendRequest(alex, bob)
                .get(5, TimeUnit.SECONDS);
        assertTrue(sent.success(), () -> plain(sent));
        assertEquals(1, store.getIncomingRequests(bob.getUniqueId()).size());
        assertTrue(plain(messagesOf(bob)).contains("Friend request from"));

        final FriendsService.Result reciprocal = service.sendRequest(bob, alex)
                .get(5, TimeUnit.SECONDS);
        assertTrue(reciprocal.success(), () -> plain(reciprocal));
        assertTrue(store.areFriends(alex.getUniqueId(), bob.getUniqueId()));
        assertEquals(1, store.friendCount(alex.getUniqueId()));
        assertTrue(store.getIncomingRequests(alex.getUniqueId()).isEmpty());
        assertTrue(store.getIncomingRequests(bob.getUniqueId()).isEmpty());
    }

    @Test
    void acceptanceDeliversAPersistentNotificationWhenTheRequesterReturns() throws Exception {
        final Player alex = offlinePlayer("Alex", true);
        final Player bob = player("Bob", true);
        store.createRequest(
                alex.getUniqueId(), "Alex", bob.getUniqueId(), "Bob",
                now.get(), now.get() + HOUR, 200, true);

        final FriendsService.Result accepted =
                service.acceptRequest(bob, alex.getUniqueId(), "Alex")
                        .get(5, TimeUnit.SECONDS);
        assertTrue(accepted.success(), () -> plain(accepted));
        assertEquals(1, store.getPendingNotifications(alex.getUniqueId()).size());

        online.put(alex.getUniqueId(), alex);
        service.onAuthenticated(alex);

        final String delivered = plain(messagesOf(alex));
        assertTrue(delivered.contains("Bob has accepted your friend request!"), delivered);
        assertTrue(store.getPendingNotifications(alex.getUniqueId()).isEmpty());
    }

    @Test
    void denialAlsoLeavesAnOfflineNotification() throws Exception {
        final Player alex = offlinePlayer("Alex", false);
        final Player bob = player("Bob", true);
        store.createRequest(
                alex.getUniqueId(), "Alex", bob.getUniqueId(), "Bob",
                now.get(), now.get() + HOUR, 200, true);

        final FriendsService.Result denied =
                service.denyRequest(bob, alex.getUniqueId(), "Alex")
                        .get(5, TimeUnit.SECONDS);
        assertTrue(denied.success(), () -> plain(denied));

        online.put(alex.getUniqueId(), alex);
        service.onAuthenticated(alex);

        final String delivered = plain(messagesOf(alex));
        assertTrue(delivered.contains("Bob has denied your friend request."), delivered);
        assertTrue(store.getPendingNotifications(alex.getUniqueId()).isEmpty());
    }

    @Test
    void configuredDefaultCanDisableRequestsForNewPlayers() throws Exception {
        config.set("friends.default-requests-enabled", false);
        service.reload();
        final Player alex = player("Alex", true);
        final Player bob = player("Bob", true);

        final FriendsService.Result result = service.sendRequest(alex, bob)
                .get(5, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(plain(result).contains("disabled friend requests"), () -> plain(result));
        assertTrue(store.getIncomingRequests(bob.getUniqueId()).isEmpty());
    }

    @Test
    void storedRequestPreferenceAlsoBlocksRequests() throws Exception {
        final Player alex = player("Alex", true);
        final Player bob = player("Bob", true);
        store.toggleRequests(bob.getUniqueId(), true, true);

        final FriendsService.Result result = service.sendRequest(alex, bob)
                .get(5, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(plain(result).contains("disabled friend requests"));
    }

    @Test
    void aFailedRequestDoesNotConsumeTheCooldown() throws Exception {
        final Player alex = player("Alex", true);
        final Player bob = player("Bob", true);
        final Player cat = player("Cat", true);
        final Player dave = player("Dave", true);
        store.createFriendship(alex.getUniqueId(), bob.getUniqueId(), now.get());

        final FriendsService.Result tooFriendly = service.sendRequest(alex, bob)
                .get(5, TimeUnit.SECONDS);
        assertFalse(tooFriendly.success());
        assertTrue(plain(tooFriendly).contains("already friends"));

        final FriendsService.Result sent = service.sendRequest(alex, cat)
                .get(5, TimeUnit.SECONDS);
        assertTrue(sent.success(), () -> plain(sent));

        final FriendsService.Result cooling = service.sendRequest(alex, dave)
                .get(5, TimeUnit.SECONDS);
        assertFalse(cooling.success());
        assertTrue(plain(cooling).contains("Wait 3s"), () -> plain(cooling));

        store.cancelRequest(alex.getUniqueId(), cat.getUniqueId());
        now.addAndGet(3_000L);
        final FriendsService.Result afterCooldown = service.sendRequest(alex, dave)
                .get(5, TimeUnit.SECONDS);
        assertTrue(afterCooldown.success(), () -> plain(afterCooldown));
    }

    @Test
    void nicknamesAreTrimmedValidatedAndReset() throws Exception {
        final Player alex = player("Alex", true);
        final Player bob = player("Bob", true);
        store.createFriendship(alex.getUniqueId(), bob.getUniqueId(), now.get());

        final FriendsService.Result set = service.setNickname(
                        alex, bob.getUniqueId(), "Bob", "  Bestie  ")
                .get(5, TimeUnit.SECONDS);
        assertTrue(set.success(), () -> plain(set));
        assertEquals("Bestie", store.getNickname(alex.getUniqueId(), bob.getUniqueId())
                .orElseThrow());

        assertFalse(service.setNickname(alex, bob.getUniqueId(), "Bob", "   ")
                .get(5, TimeUnit.SECONDS).success());
        assertFalse(service.setNickname(alex, bob.getUniqueId(), "Bob", "&cRed")
                .get(5, TimeUnit.SECONDS).success());
        assertFalse(service.setNickname(alex, bob.getUniqueId(), "Bob", "Bad\u0007Name")
                .get(5, TimeUnit.SECONDS).success());
        assertFalse(service.setNickname(
                                alex,
                                bob.getUniqueId(),
                                "Bob",
                                "0123456789012345678901234")
                        .get(5, TimeUnit.SECONDS)
                        .success());
        assertEquals("Bestie", store.getNickname(alex.getUniqueId(), bob.getUniqueId())
                .orElseThrow());

        final FriendsService.Result reset = service.setNickname(
                        alex, bob.getUniqueId(), "Bob", "reset")
                .get(5, TimeUnit.SECONDS);
        assertTrue(reset.success(), () -> plain(reset));
        assertTrue(store.getNickname(alex.getUniqueId(), bob.getUniqueId()).isEmpty());
    }

    @Test
    void friendListUsesCachedOfflineNamesAndTheRankPrefix() throws Exception {
        final Player alex = player("Alex", true);
        final Player bob = offlinePlayer("Bob", true);
        store.rememberName(alex.getUniqueId(), "Alex", now.get());
        store.rememberName(bob.getUniqueId(), "Bob", now.get());
        store.createFriendship(alex.getUniqueId(), bob.getUniqueId(), now.get());
        ranks.assign(bob.getUniqueId(), "Bob", "Sweat");

        final FriendsService.Result result = service.openFriendList(alex, 1)
                .get(5, TimeUnit.SECONDS);

        assertTrue(result.success());
        final String text = plain(result);
        assertTrue(text.contains("Sweat"), text);
        assertTrue(text.contains("Bob"), text);
        assertFalse(text.contains("(online)"), text);
    }

    @Test
    void friendLimitsAndBestFriendLimitsAreEnforcedByTheService() throws Exception {
        config.set("friends.max-friends", 1);
        config.set("friends.max-best-friends", 1);
        service.reload();

        final Player alex = player("Alex", true);
        final Player bob = player("Bob", true);
        final Player cat = player("Cat", true);
        store.createFriendship(alex.getUniqueId(), bob.getUniqueId(), now.get());
        store.createFriendship(alex.getUniqueId(), cat.getUniqueId(), now.get());

        final FriendsService.Result full = service.sendRequest(alex, player("Dave", true))
                .get(5, TimeUnit.SECONDS);
        assertFalse(full.success());
        assertTrue(plain(full).contains("friends list is full"));

        assertTrue(service.toggleBestFriend(alex, bob.getUniqueId(), "Bob")
                .get(5, TimeUnit.SECONDS)
                .success());
        final FriendsService.Result bestLimit =
                service.toggleBestFriend(alex, cat.getUniqueId(), "Cat")
                        .get(5, TimeUnit.SECONDS);
        assertFalse(bestLimit.success());
        assertTrue(plain(bestLimit).contains("only have 1 best friend"));
    }

    @Test
    void presenceNotificationsReachOnlyFriendsWhoHaveNotificationsEnabled() {
        final Player alex = player("Alex", true);
        final Player bob = player("Bob", true);
        store.createFriendship(alex.getUniqueId(), bob.getUniqueId(), now.get());

        service.onAuthenticated(alex);
        assertTrue(
                plain(messagesOf(bob)).contains("Alex has joined the server."),
                () -> plain(messagesOf(bob)));

        service.onQuit(new PlayerQuitEvent(
                alex, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertTrue(
                plain(messagesOf(bob)).contains("Alex has left the server."),
                () -> plain(messagesOf(bob)));

        store.toggleNotifications(bob.getUniqueId(), true, true);
        inboxes.get(bob.getUniqueId()).clear();
        service.onQuit(new PlayerQuitEvent(
                alex, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(plain(messagesOf(bob)).contains("has left the server."));
    }

    @Test
    void deletePlayerDataRemovesFriendshipsNicknamesAndNotifications() throws Exception {
        final Player alex = player("Alex", true);
        final Player bob = player("Bob", true);
        store.createRequest(
                alex.getUniqueId(), "Alex", bob.getUniqueId(), "Bob",
                now.get(), now.get() + HOUR, 200, true);
        store.denyRequest(alex.getUniqueId(), bob.getUniqueId(), "Bob", now.get());
        store.createFriendship(alex.getUniqueId(), bob.getUniqueId(), now.get());
        store.setNickname(alex.getUniqueId(), bob.getUniqueId(), "Bestie");

        final FriendsService.Result deleted = service.deletePlayerData(alex.getUniqueId())
                .get(5, TimeUnit.SECONDS);

        assertTrue(deleted.success());
        assertTrue(store.getFriendships(alex.getUniqueId()).isEmpty());
        assertTrue(store.getFriendships(bob.getUniqueId()).isEmpty());
        assertTrue(store.getNickname(alex.getUniqueId(), bob.getUniqueId()).isEmpty());
        assertTrue(store.getPendingNotifications(alex.getUniqueId()).isEmpty());
    }

    @Test
    void aFullDatabaseQueueReturnsABusyMessageInsteadOfBlocking() throws Exception {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                running.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            executor.execute(() -> {});

            final FriendsService busy = service(executor);
            final Player alex = player("Alex", true);
            final Player bob = player("Bob", true);
            try {
                final FriendsService.Result result = busy.sendRequest(alex, bob)
                        .get(5, TimeUnit.SECONDS);
                assertFalse(result.success());
                assertTrue(plain(result).contains("busy"), () -> plain(result));
            } finally {
                busy.close();
            }
        } catch (final RejectedExecutionException exception) {
            throw new AssertionError("The test queue was unexpectedly rejected", exception);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private FriendsService service(final java.util.concurrent.Executor executor) {
        return new FriendsService(
                TestPlugins.plugin(config),
                ranks,
                permissions,
                id -> true,
                warnings::add,
                now::get,
                store,
                executor,
                Runnable::run,
                online::get,
                uuid -> true);
    }

    private Player player(final String name, final boolean permission) {
        final List<Component> messages = new ArrayList<>();
        final Player player = TestCommandSenders.player(name, 0, permission, messages);
        inboxes.put(player.getUniqueId(), messages);
        online.put(player.getUniqueId(), player);
        return player;
    }

    private Player offlinePlayer(final String name, final boolean permission) {
        final List<Component> messages = new ArrayList<>();
        final Player player = TestCommandSenders.player(name, 0, permission, messages);
        inboxes.put(player.getUniqueId(), messages);
        return player;
    }

    private List<Component> messagesOf(final Player player) {
        return inboxes.getOrDefault(player.getUniqueId(), List.of());
    }

    private static String plain(final FriendsService.Result result) {
        return plain(result.messages());
    }

    private static String plain(final List<Component> messages) {
        final StringBuilder out = new StringBuilder();
        for (final Component message : messages) {
            out.append(TestCommandSenders.plain(message)).append('\n');
        }
        return out.toString();
    }
}
