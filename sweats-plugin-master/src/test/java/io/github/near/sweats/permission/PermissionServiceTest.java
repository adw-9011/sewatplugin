package io.github.near.sweats.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void userGrantsAreExplicitDuplicateSafeAndPersistent() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();

        assertFalse(permissions.has(id, PermissionNodes.BAN));
        assertTrue(permissions.grantUser(id, "Alex", "sweats.banperm"));
        assertTrue(permissions.has(id, PermissionNodes.BAN));
        assertTrue(permissions.has(id, "SWEATS.BANPERM"));
        assertFalse(permissions.grantUser(id, "Alex", "sweats.banperm"));
        assertEquals(List.of("sweats.banperm"), permissions.userPermissions(id));
        assertTrue(permissions.knownUser("alex").isPresent());

        final PermissionService reloaded =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        assertTrue(reloaded.has(id, PermissionNodes.BAN));

        assertTrue(reloaded.revokeUser(id, "sweats.banperm"));
        assertFalse(reloaded.has(id, PermissionNodes.BAN));
        assertFalse(reloaded.revokeUser(id, "sweats.banperm"));
    }

    @Test
    void rankGrantsApplyToPlayersWithThatRank() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();
        ranks.assign(id, "Alex", "Admin");

        assertFalse(permissions.has(id, PermissionNodes.MUTE));
        assertTrue(permissions.grantRank("admin", "sweats.muteperm"));
        assertTrue(permissions.has(id, PermissionNodes.MUTE));
        assertEquals(List.of("sweats.muteperm"), permissions.rankPermissions(ranks.find("Admin").orElseThrow()));

        final PermissionService reloaded =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        assertTrue(reloaded.has(id, PermissionNodes.MUTE));

        assertTrue(reloaded.revokeRank("Admin", "sweats.muteperm"));
        assertFalse(reloaded.has(id, PermissionNodes.MUTE));
    }

    @Test
    void invalidNodesAreRejected() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        final UUID id = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> permissions.grantUser(id, "Alex", "not a node"));
        assertThrows(IllegalArgumentException.class,
                () -> permissions.grantRank("Admin", "**"));
        assertFalse(permissions.has(id, "not a node"));
    }

    @Test
    void deletedRanksLoseTheirPermissions() {
        final RankService ranks = new RankService(folder, warnings::add);
        final PermissionService permissions =
                new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        ranks.create("&6Moderator", 25);
        permissions.grantRank("Moderator", "sweats.kickperm");

        ranks.delete("Moderator");
        permissions.rankListChanged();
        ranks.create("&6Moderator", 25);

        assertTrue(permissions.rankPermissions(ranks.find("Moderator").orElseThrow()).isEmpty());
    }
}
