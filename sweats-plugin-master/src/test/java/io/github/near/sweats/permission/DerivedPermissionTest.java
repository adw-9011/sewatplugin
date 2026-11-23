package io.github.near.sweats.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DerivedPermissionTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();

    private static Rank rank(final String name, final int weight) {
        return new Rank(name, Component.text(name, NamedTextColor.GRAY), weight, true);
    }

    @Test
    void eachRankDerivesExactlyItsCapabilities() {
        // Base permissions that ALL ranks get
        final Set<String> base = Set.of(PermissionNodes.FRIEND, PermissionNodes.FRIEND_REQUESTS);

        assertEquals(base, PermissionNodes.derivedFor(rank("Default", 0)));
        assertEquals(
                union(base, Set.of(PermissionNodes.CHAT_WHITE, PermissionNodes.SHOW, PermissionNodes.STATS)),
                PermissionNodes.derivedFor(rank("Grinder", 10)));
        assertEquals(
                union(base, Set.of(
                        PermissionNodes.CHAT_WHITE,
                        PermissionNodes.CHAT_MEMBER,
                        PermissionNodes.BETA_ACCESS,
                        PermissionNodes.SHOW,
                        PermissionNodes.STATS,
                        PermissionNodes.NICK)),
                PermissionNodes.derivedFor(rank("Sweat", 20)));
        assertEquals(
                union(base, Set.of(
                        PermissionNodes.CHAT_WHITE,
                        PermissionNodes.CHAT_MEMBER,
                        PermissionNodes.BETA_ACCESS,
                        PermissionNodes.PLUS_COLOR,
                        PermissionNodes.SHOW,
                        PermissionNodes.STATS,
                        PermissionNodes.NICK,
                        PermissionNodes.SCARE,
                        PermissionNodes.GIF)),
                PermissionNodes.derivedFor(rank("Sweat+", 30)));
        assertEquals(
                union(base, Set.of(
                        PermissionNodes.CHAT_WHITE,
                        PermissionNodes.CHAT_MEMBER,
                        PermissionNodes.BETA_ACCESS,
                        PermissionNodes.SHOW,
                        PermissionNodes.STATS,
                        PermissionNodes.NICK,
                        PermissionNodes.SCARE,
                        PermissionNodes.GIF,
                        PermissionNodes.CUSTOM_RANK)),
                PermissionNodes.derivedFor(rank("Custom", 35)));
        assertEquals(
                union(base, Set.of(
                        PermissionNodes.CHAT_WHITE,
                        PermissionNodes.CHAT_MEMBER,
                        PermissionNodes.BETA_ACCESS,
                        PermissionNodes.SHOW,
                        PermissionNodes.STATS,
                        PermissionNodes.NICK,
                        PermissionNodes.SCARE,
                        PermissionNodes.GIF,
                        PermissionNodes.CUSTOM_RANK,
                        PermissionNodes.INVESTOR_DISCORD)),
                PermissionNodes.derivedFor(rank("Investor", 38)));
        assertEquals(
                union(base, Set.of(
                        PermissionNodes.CHAT_WHITE,
                        PermissionNodes.CHAT_MEMBER,
                        PermissionNodes.BETA_ACCESS,
                        PermissionNodes.SHOW,
                        PermissionNodes.STATS,
                        PermissionNodes.NICK,
                        PermissionNodes.SCARE,
                        PermissionNodes.GIF)),
                PermissionNodes.derivedFor(rank("Admin", 40)));
        assertEquals(
                union(base, Set.of(
                        PermissionNodes.CHAT_WHITE,
                        PermissionNodes.CHAT_MEMBER,
                        PermissionNodes.BETA_ACCESS,
                        PermissionNodes.SHOW,
                        PermissionNodes.STATS,
                        PermissionNodes.NICK,
                        PermissionNodes.SCARE,
                        PermissionNodes.GIF,
                        PermissionNodes.CURRENCY_GIVE)),
                PermissionNodes.derivedFor(rank("CM", 50)));
        assertEquals(
                union(base, Set.of(
                        PermissionNodes.CHAT_WHITE,
                        PermissionNodes.CHAT_MEMBER,
                        PermissionNodes.BETA_ACCESS,
                        PermissionNodes.SHOW,
                        PermissionNodes.STATS,
                        PermissionNodes.NICK,
                        PermissionNodes.SCARE,
                        PermissionNodes.GIF,
                        PermissionNodes.VANISH,
                        PermissionNodes.CURRENCY_GIVE)),
                PermissionNodes.derivedFor(rank("DEV", 60)));
    }

    private static Set<String> union(final Set<String> base, final Set<String> extra) {
        final Set<String> result = new LinkedHashSet<>(base);
        result.addAll(extra);
        return result;
    }

    @Test
    void everyRegisteredNodeIsOfferedByGrantCompletion() {
        for (final String node : List.of(
                PermissionNodes.HOME,
                PermissionNodes.HOMESET,
                PermissionNodes.VANISH,
                PermissionNodes.LIST,
                PermissionNodes.TEAM_CREATE,
                PermissionNodes.TEAM_DELETE,
                PermissionNodes.TEAM_INVITE,
                PermissionNodes.TEAM_MANAGE,
                PermissionNodes.TEAM_CHAT,
                PermissionNodes.TPA,
                PermissionNodes.TPA_BYPASS_COOLDOWN,
                PermissionNodes.BETA_ACCESS,
                PermissionNodes.CHAT_WHITE,
                PermissionNodes.CHAT_MEMBER,
                PermissionNodes.PLUS_COLOR,
                PermissionNodes.NICK,
                PermissionNodes.NICK_OTHERS,
                PermissionNodes.SHOW,
                PermissionNodes.STATS,
                PermissionNodes.SCARE,
                PermissionNodes.GIF,
                PermissionNodes.CUSTOM_RANK,
                PermissionNodes.INVESTOR_DISCORD,
                PermissionNodes.CURRENCY_GIVE,
                PermissionNodes.MESSAGE,
                PermissionNodes.MESSAGE_BYPASS_COOLDOWN,
                PermissionNodes.DUPE_ALERTS)) {
            assertTrue(PermissionNodes.ALL.contains(node), node);
            assertTrue(PermissionNodes.isValid(node), node);
        }
    }

    @Test
    void rankChangesRecalculateDerivedNodesImmediately() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();

        assertFalse(permissions.has(id, PermissionNodes.CHAT_WHITE));
        assertFalse(permissions.has(id, PermissionNodes.CHAT_MEMBER));
        assertFalse(permissions.has(id, PermissionNodes.SHOW));
        assertFalse(permissions.has(id, PermissionNodes.STATS));

        ranks.assign(id, "Alex", "Sweat");
        assertTrue(permissions.has(id, PermissionNodes.CHAT_WHITE));
        assertTrue(permissions.has(id, PermissionNodes.CHAT_MEMBER));
        assertTrue(permissions.has(id, PermissionNodes.BETA_ACCESS));
        assertTrue(permissions.has(id, PermissionNodes.SHOW));
        assertTrue(permissions.has(id, PermissionNodes.STATS));
        assertTrue(permissions.has(id, PermissionNodes.NICK));
        assertFalse(permissions.has(id, PermissionNodes.PLUS_COLOR));
        assertFalse(permissions.has(id, PermissionNodes.VANISH));
        assertTrue(permissions.userPermissions(id).isEmpty(),
                "derived nodes must not become manual grants");

        ranks.assign(id, "Alex", "Default");
        assertFalse(permissions.has(id, PermissionNodes.CHAT_WHITE));
        assertFalse(permissions.has(id, PermissionNodes.CHAT_MEMBER));
        assertFalse(permissions.has(id, PermissionNodes.SHOW));
        assertFalse(permissions.has(id, PermissionNodes.STATS));

        final org.bukkit.configuration.file.YamlConfiguration stored =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        folder.resolve("permissions.yml").toFile());
        final var users = stored.getConfigurationSection("users");
        final var rankNodes = stored.getConfigurationSection("ranks");
        assertTrue(users == null || users.getKeys(false).isEmpty(),
                "derived nodes must not become stored user grants");
        assertTrue(rankNodes == null || rankNodes.getKeys(false).isEmpty(),
                "derived nodes must not become stored rank grants");
    }

    @Test
    void manualGrantsSurviveRankChanges() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();

        assertTrue(permissions.grantUser(id, "Alex", PermissionNodes.BETA_ACCESS));
        assertEquals(List.of(PermissionNodes.BETA_ACCESS), permissions.userPermissions(id));

        ranks.assign(id, "Alex", "Grinder");
        assertTrue(permissions.has(id, PermissionNodes.BETA_ACCESS));
        assertTrue(permissions.has(id, PermissionNodes.CHAT_WHITE));
        assertTrue(permissions.has(id, PermissionNodes.SHOW));
        assertTrue(permissions.has(id, PermissionNodes.STATS));
        assertFalse(permissions.has(id, PermissionNodes.CHAT_MEMBER));

        assertTrue(permissions.revokeUser(id, PermissionNodes.BETA_ACCESS));
        assertFalse(permissions.has(id, PermissionNodes.BETA_ACCESS));
    }

    @Test
    void commandSenderChecksSeeDerivedNodesToo() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        final List<Component> messages = new ArrayList<>();
        final CommandSender sender = TestCommandSenders.player("Alex", 0, false, messages);

        ranks.assign(sender instanceof final org.bukkit.entity.Player player
                        ? player.getUniqueId()
                        : UUID.randomUUID(),
                "Alex",
                "Sweat+");

        assertTrue(permissions.has(sender, PermissionNodes.CHAT_MEMBER));
        assertTrue(permissions.has(sender, PermissionNodes.PLUS_COLOR));
        assertTrue(permissions.has(sender, PermissionNodes.CHAT_WHITE));
        assertTrue(permissions.has(sender, PermissionNodes.SHOW));
        assertTrue(permissions.has(sender, PermissionNodes.STATS));
        assertTrue(permissions.has(sender, PermissionNodes.NICK));
        assertTrue(permissions.has(sender, PermissionNodes.SCARE));
        assertTrue(permissions.has(sender, PermissionNodes.GIF));
    }

    @Test
    void devRanksReceiveVanishButAdminsDoNot() {
        final RankService ranks = new RankService(folder, warnings::add);
        final UUID admin = UUID.randomUUID();
        final UUID dev = UUID.randomUUID();

        ranks.assign(admin, "Admin", "Admin");
        ranks.assign(dev, "Dev", "DEV");

        final Set<String> base = Set.of(PermissionNodes.FRIEND, PermissionNodes.FRIEND_REQUESTS);
        assertEquals(
                union(base, Set.of(
                        PermissionNodes.CHAT_WHITE,
                        PermissionNodes.CHAT_MEMBER,
                        PermissionNodes.BETA_ACCESS,
                        PermissionNodes.SHOW,
                        PermissionNodes.STATS,
                        PermissionNodes.NICK,
                        PermissionNodes.SCARE,
                        PermissionNodes.GIF)),
                PermissionNodes.derivedFor(ranks.rankOf(admin)));
        assertEquals(
                union(base, Set.of(
                        PermissionNodes.CHAT_WHITE,
                        PermissionNodes.CHAT_MEMBER,
                        PermissionNodes.BETA_ACCESS,
                        PermissionNodes.SHOW,
                        PermissionNodes.STATS,
                        PermissionNodes.NICK,
                        PermissionNodes.SCARE,
                        PermissionNodes.GIF,
                        PermissionNodes.VANISH,
                        PermissionNodes.CURRENCY_GIVE)),
                PermissionNodes.derivedFor(ranks.rankOf(dev)));
    }
}