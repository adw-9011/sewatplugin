package io.github.near.sweats.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.teams.TeamService.LeaveResult;
import io.github.near.sweats.teams.TeamService.Team;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeamServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    private RankService ranks;
    private TeamService teams;
    private long now = 1_000_000L;

    @BeforeEach
    void setUp() {
        ranks = new RankService(folder, warnings::add);
        teams = new TeamService(folder, ranks, warnings::add, () -> now);
        teams.reloadSettings(4, 60);
    }

    private void join(final UUID owner, final String teamName, final UUID target) {
        teams.invite(owner, target);
        teams.accept(target, teamName);
    }

    @Test
    void createStoresOwnerMembershipAndIsCaseInsensitiveForNames() {
        final Team team = teams.create(alice, "Alpha");

        assertEquals("Alpha", team.name());
        assertEquals(alice, team.owner());
        assertEquals(List.of(alice), team.members());
        assertTrue(teams.teamOf(alice).isPresent());
        assertEquals(team.id(), teams.teamByName("alpha").orElseThrow().id());

        assertThrows(IllegalArgumentException.class, () -> teams.create(alice, "Beta"));
        assertThrows(IllegalArgumentException.class, () -> teams.create(bob, "ALPHA"));
        assertTrue(teams.teamOf(bob).isEmpty());
    }

    @Test
    void namesAreRestrictedToClickableSafeCharacters() {
        for (final String invalid : List.of("", "  ", "bad name", "team!", "a".repeat(21), "téam")) {
            assertThrows(IllegalArgumentException.class, () -> teams.create(alice, invalid), invalid);
        }

        assertEquals("Team_1+-", teams.create(alice, " Team_1+- ").name());
    }

    @Test
    void capacityFollowsTheOwnersCurrentRankAndConfiguredBase() {
        assertEquals(4, teams.capacity(alice));

        ranks.assign(alice, "Alice", "Grinder");
        assertEquals(4, teams.capacity(alice));
        ranks.assign(alice, "Alice", "Sweat");
        assertEquals(6, teams.capacity(alice));
        ranks.assign(alice, "Alice", "Sweat+");
        assertEquals(10, teams.capacity(alice));
        ranks.assign(alice, "Alice", "Admin");
        assertEquals(10, teams.capacity(alice));

        teams.reloadSettings(3, 60);
        ranks.assign(alice, "Alice", "Default");
        assertEquals(3, teams.capacity(alice));
        assertEquals(3, teams.capacity(bob));
    }

    @Test
    void invitesAreOwnerOnlyAndRespectCapacity() {
        teams.create(alice, "Alpha");
        join(alice, "Alpha", bob);

        assertThrows(IllegalArgumentException.class, () -> teams.invite(bob, carol));
        assertThrows(IllegalArgumentException.class, () -> teams.invite(alice, alice));
        assertThrows(IllegalArgumentException.class, () -> teams.invite(alice, bob));

        teams.reloadSettings(2, 60);
        assertThrows(IllegalArgumentException.class, () -> teams.invite(alice, carol));

        teams.reloadSettings(4, 60);
        assertEquals("Alpha", teams.invite(alice, carol).teamName());
        assertThrows(IllegalArgumentException.class, () -> teams.invite(alice, carol));
    }

    @Test
    void aNewerInvitationReplacesAnOlderOneForTheSamePlayer() {
        teams.create(alice, "Alpha");
        teams.create(carol, "Gamma");

        teams.invite(alice, bob);
        teams.invite(carol, bob);

        assertEquals("Gamma", teams.invitation(bob).orElseThrow().teamName());
        assertThrows(IllegalArgumentException.class, () -> teams.accept(bob, "Alpha"));
        assertEquals("Gamma", teams.invitation(bob).orElseThrow().teamName());

        assertEquals("Gamma", teams.accept(bob, "Gamma").name());
        assertTrue(teams.teamOf(bob).isPresent());
        assertTrue(teams.invitation(bob).isEmpty());
    }

    @Test
    void invitationsExpireAndAreRemovedLazily() {
        teams.create(alice, "Alpha");
        teams.invite(alice, bob);

        now += 59_999L;
        assertTrue(teams.invitation(bob).isPresent());

        now += 1L;
        assertTrue(teams.invitation(bob).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> teams.accept(bob, "Alpha"));
        assertThrows(IllegalArgumentException.class, () -> teams.deny(bob, "Alpha"));
    }

    @Test
    void acceptingRevalidatesTheTeamAfterItWasDeleted() {
        teams.create(alice, "Alpha");
        teams.invite(alice, bob);
        teams.delete(alice, false);

        assertThrows(IllegalArgumentException.class, () -> teams.accept(bob, "Alpha"));
        assertTrue(teams.invitation(bob).isEmpty());
        assertTrue(teams.teamOf(bob).isEmpty());
    }

    @Test
    void acceptingRevalidatesThatTheInviterStillOwnsTheTeam() {
        teams.create(alice, "Alpha");
        join(alice, "Alpha", bob);
        teams.invite(alice, carol);

        assertEquals(bob, teams.leave(alice).newOwner());

        assertThrows(IllegalArgumentException.class, () -> teams.accept(carol, "Alpha"));
        assertTrue(teams.invitation(carol).isEmpty());
        assertTrue(teams.teamOf(carol).isEmpty());
        assertEquals(bob, teams.teamOf(bob).orElseThrow().owner());
    }

    @Test
    void acceptingRejectsAPlayerWhoJoinedAnotherTeamMeanwhile() {
        teams.create(alice, "Alpha");
        teams.invite(alice, bob);
        teams.create(bob, "Solo");

        assertThrows(IllegalArgumentException.class, () -> teams.accept(bob, "Alpha"));
        assertTrue(teams.invitation(bob).isEmpty());
        assertEquals("Solo", teams.teamOf(bob).orElseThrow().name());
    }

    @Test
    void acceptingFailsIfTheTeamDroppedBelowItsCurrentSize() {
        teams.reloadSettings(2, 60);
        teams.create(alice, "Alpha");
        teams.invite(alice, bob);

        teams.reloadSettings(1, 60);
        assertThrows(IllegalArgumentException.class, () -> teams.accept(bob, "Alpha"));
        assertTrue(teams.invitation(bob).isPresent());

        teams.reloadSettings(2, 60);
        assertEquals("Alpha", teams.accept(bob, "Alpha").name());
    }

    @Test
    void denyChecksTheTeamNameAndRemovesTheInvitation() {
        teams.create(alice, "Alpha");
        teams.invite(alice, bob);

        assertThrows(IllegalArgumentException.class, () -> teams.deny(bob, "Other"));
        assertTrue(teams.invitation(bob).isPresent());

        assertEquals("Alpha", teams.deny(bob, "alpha"));
        assertTrue(teams.invitation(bob).isEmpty());
    }

    @Test
    void leavingSoloDeletesTheTeamAndOwnerLeavingTransfersOwnership() {
        teams.create(alice, "Alpha");
        assertEquals(new LeaveResult("Alpha", null, true), teams.leave(alice));
        assertTrue(teams.teamOf(alice).isEmpty());
        assertTrue(teams.teamByName("Alpha").isEmpty());

        teams.create(alice, "Alpha");
        join(alice, "Alpha", bob);
        join(alice, "Alpha", carol);

        assertEquals(new LeaveResult("Alpha", bob, false), teams.leave(alice));
        assertEquals(bob, teams.teamOf(bob).orElseThrow().owner());
        assertEquals(List.of(bob, carol), teams.teamOf(carol).orElseThrow().members());

        assertNull(teams.leave(carol).newOwner());
        assertEquals(bob, teams.teamOf(bob).orElseThrow().owner());
        assertEquals(List.of(bob), teams.teamOf(bob).orElseThrow().members());
    }

    @Test
    void kickingRequiresOwnershipAndNeverRemovesTheOwner() {
        teams.create(alice, "Alpha");
        join(alice, "Alpha", bob);

        assertThrows(IllegalArgumentException.class, () -> teams.kick(bob, alice, false));
        assertThrows(IllegalArgumentException.class, () -> teams.kick(alice, alice, false));
        assertThrows(IllegalArgumentException.class, () -> teams.kick(alice, alice, true));
        assertThrows(IllegalArgumentException.class, () -> teams.kick(alice, carol, false));

        teams.kick(alice, bob, false);
        assertFalse(teams.teamOf(bob).isPresent());
        assertEquals(List.of(alice), teams.teamOf(alice).orElseThrow().members());

        join(alice, "Alpha", bob);
        teams.kick(carol, bob, true);
        assertFalse(teams.teamOf(bob).isPresent());
    }

    @Test
    void deletingRequiresTheOwnerUnlessStaffUseTheExplicitOverride() {
        teams.create(alice, "Alpha");
        join(alice, "Alpha", bob);

        assertThrows(IllegalArgumentException.class, () -> teams.delete(bob, false));
        assertThrows(IllegalArgumentException.class, () -> teams.deleteByName("Alpha", false));
        assertThrows(IllegalArgumentException.class, () -> teams.deleteByName("Unknown", true));

        assertEquals("Alpha", teams.deleteByName("Alpha", true));
        assertTrue(teams.teamOf(alice).isEmpty());
    }

    @Test
    void deletingClearsPendingInvitationsForThatTeam() {
        teams.create(alice, "Alpha");
        teams.invite(alice, bob);

        teams.delete(alice, false);

        assertTrue(teams.invitation(bob).isEmpty());
    }

    @Test
    void teamsSurviveReloadAndRankChanges() {
        teams.create(alice, "Alpha");
        join(alice, "Alpha", bob);
        ranks.assign(alice, "Alice", "Sweat+");

        final TeamService reloaded =
                new TeamService(folder, ranks, warnings::add, () -> now);
        final Team team = reloaded.teamByName("Alpha").orElseThrow();

        assertEquals(alice, team.owner());
        assertEquals(List.of(alice, bob), team.members());
        assertEquals(10, reloaded.capacity(alice));
        assertEquals(4, reloaded.capacity(bob));
    }

    @Test
    void disconnectingClearsInvitationsInEitherDirection() {
        teams.create(alice, "Alpha");
        teams.invite(alice, bob);
        teams.handleQuit(bob);
        assertTrue(teams.invitation(bob).isEmpty());

        teams.invite(alice, bob);
        teams.handleQuit(alice);
        assertTrue(teams.invitation(bob).isEmpty());

        teams.invite(alice, bob);
        teams.close();
        assertTrue(teams.invitation(bob).isEmpty());
    }
}
