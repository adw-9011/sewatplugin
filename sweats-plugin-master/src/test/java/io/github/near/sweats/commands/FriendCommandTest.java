package io.github.near.sweats.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.friends.FriendsService;
import io.github.near.sweats.friends.FriendsStore;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FriendCommandTest {

    private static final long NOW = 1_700_000_000_000L;

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private final java.util.Map<UUID, List<Component>> inboxes = new java.util.HashMap<>();
    private RankService ranks;
    private PermissionService permissions;
    private FriendsStore store;
    private FriendsService service;
    private FriendCommand command;

    @BeforeEach
    void setUp() throws Exception {
        final YamlConfiguration config = new YamlConfiguration();
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
        service = new FriendsService(
                TestPlugins.plugin(config),
                ranks,
                permissions,
                id -> true,
                warnings::add,
                () -> NOW,
                store,
                Runnable::run,
                Runnable::run,
                id -> null,
                uuid -> true);
        command = new FriendCommand(service, permissions);
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
    void theFriendPermissionNodeIsTheOneEnforced() {
        final Player alex = player("Alex", false);
        permissions.grantUser(alex.getUniqueId(), "Alex", PermissionNodes.FRIEND);

        run(alex, "friend");

        assertTrue(plain(alex).contains("Friend commands:"), () -> plain(alex));
    }

    @Test
    void consoleCannotUseFriendCommand() {
        final List<Component> messages = new ArrayList<>();
        final CommandSender console = TestCommandSenders.console(true, messages);

        command.onCommand(console, null, "friend", new String[0]);

        assertTrue(plain(messages).contains("Only players"), () -> plain(messages));
    }

    @Test
    void theConsoleCannotRunPlayerCommands() {
        final List<Component> messages = new ArrayList<>();
        final CommandSender console = TestCommandSenders.console(true, messages);

        command.onCommand(console, null, "friend", new String[0]);

        assertTrue(plain(messages).contains("Only players"), () -> plain(messages));
    }

    @Test
    void noArgumentsShowsUsageForBothLabels() {
        final Player alex = player("Alex", false);
        permissions.grantUser(alex.getUniqueId(), "Alex", PermissionNodes.FRIEND);

        run(alex, "f");

        assertTrue(plain(alex).contains("Friend commands:"), () -> plain(alex));
        assertTrue(plain(alex).contains("/f add"), () -> plain(alex));
    }

    @Test
    void listShowsCachedFriendNames() {
        final Player alex = player("Alex", false);
        final UUID bob = UUID.randomUUID();
        permissions.grantUser(alex.getUniqueId(), "Alex", PermissionNodes.FRIEND);
        store.rememberName(alex.getUniqueId(), "Alex", NOW);
        store.rememberName(bob, "Bob", NOW);
        store.createFriendship(alex.getUniqueId(), bob, NOW);

        run(alex, "friend", "list");

        assertTrue(plain(alex).contains("Friends (1/200)"), () -> plain(alex));
        assertTrue(plain(alex).contains("Bob"), () -> plain(alex));
    }

    @Test
    void anEmptyListExplainsThatThereAreNoFriends() {
        final Player alex = player("Alex", false);
        permissions.grantUser(alex.getUniqueId(), "Alex", PermissionNodes.FRIEND);

        run(alex, "friend", "list");

        assertTrue(plain(alex).contains("You have no friends yet."), () -> plain(alex));
    }

    @Test
    void invalidPagesAndMissingArgumentsShowUsage() {
        final Player alex = player("Alex", false);
        permissions.grantUser(alex.getUniqueId(), "Alex", PermissionNodes.FRIEND);

        run(alex, "friend", "list", "nope");
        assertTrue(plain(alex).contains("Invalid page number."), () -> plain(alex));

        run(alex, "friend", "add");
        assertTrue(plain(alex).contains("Usage: /friend add <player>"), () -> plain(alex));

        run(alex, "friend", "accept");
        assertTrue(plain(alex).contains("Usage: /friend accept <player>"), () -> plain(alex));

        run(alex, "friend", "nickname", "Bob");
        assertTrue(
                plain(alex).contains("Usage: /friend nickname <player> <nickname|reset>"),
                () -> plain(alex));
    }

    @Test
    void unknownSubcommandsAreReported() {
        final Player alex = player("Alex", false);
        permissions.grantUser(alex.getUniqueId(), "Alex", PermissionNodes.FRIEND);

        run(alex, "friend", "wat", "extra");

        assertTrue(plain(alex).contains("Unknown subcommand: wat"), () -> plain(alex));
    }

    @Test
    void notificationAndRequestPreferencesCanBeToggled() {
        final Player alex = player("Alex", false);
        permissions.grantUser(alex.getUniqueId(), "Alex", PermissionNodes.FRIEND);

        run(alex, "friend", "notifications");
        assertTrue(plain(alex).contains("Friend notifications disabled"), () -> plain(alex));
        assertFalse(store.getNotificationsEnabled(alex.getUniqueId(), true));

        run(alex, "friend", "toggle");
        assertTrue(plain(alex).contains("Friend requests disabled"), () -> plain(alex));
        assertFalse(store.getRequestsEnabled(alex.getUniqueId(), true));
    }

    private void run(final Player player, final String... args) {
        command.onCommand(player, null, args[0], java.util.Arrays.copyOfRange(args, 1, args.length));
    }

    private Player player(final String name, final boolean permission) {
        final List<Component> messages = new ArrayList<>();
        final Player player = TestCommandSenders.player(name, 0, permission, messages);
        inboxes.put(player.getUniqueId(), messages);
        return player;
    }

    private String plain(final Player player) {
        return plain(inboxes.getOrDefault(player.getUniqueId(), List.of()));
    }

    private static String plain(final List<Component> messages) {
        final StringBuilder out = new StringBuilder();
        for (final Component message : messages) {
            out.append(TestCommandSenders.plain(message)).append('\n');
        }
        return out.toString();
    }
}
