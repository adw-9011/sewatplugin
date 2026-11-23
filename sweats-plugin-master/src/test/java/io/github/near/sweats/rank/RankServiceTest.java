package io.github.near.sweats.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RankServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void coreRanksAlwaysExistInDescendingWeightOrder() {
        final RankService ranks = new RankService(folder, warnings::add);

        assertEquals(
                List.of("DEV", "CM", "Admin", "Investor", "Custom", "Sweat+", "Sweat", "Grinder", "Default"),
                ranks.ranks().stream().map(Rank::name).toList());
        assertTrue(ranks.ranks().stream().allMatch(Rank::core));
        assertEquals("Default", ranks.defaultRank().name());
        assertEquals(0, ranks.defaultRank().weight());
        assertEquals(60, ranks.find("dev").orElseThrow().weight());
        assertEquals(NamedTextColor.GREEN, TestCommandSenders.colorOf(ranks.find("Grinder").orElseThrow().displayName(), "Grinder"));
        assertEquals(0, ranks.ranks().getLast().weight());
    }

    @Test
    void createsCaseInsensitiveRanksAndKeepsDisplayCapitalisation() {
        final RankService ranks = new RankService(folder, warnings::add);

        final Rank created = ranks.create("&aEvent_Team", 25);

        assertEquals("Event_Team", created.name());
        assertEquals(NamedTextColor.GREEN, TestCommandSenders.colorOf(created.displayName(), "Event_Team"));
        assertFalse(created.core());
        assertEquals(created, ranks.find("event team").orElseThrow());
        assertEquals(created, ranks.find("EVENT_TEAM").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> ranks.create("event team", 1));
    }

    @Test
    void equalWeightsSortAlphabetically() {
        final RankService ranks = new RankService(folder, warnings::add);
        ranks.create("&cZeta", 25);
        ranks.create("&bAlpha", 25);

        final List<String> names = ranks.ranks().stream().map(Rank::name).toList();

        assertTrue(names.indexOf("Alpha") < names.indexOf("Zeta"));
        assertTrue(names.indexOf("Sweat+") < names.indexOf("Alpha"));
        assertTrue(names.indexOf("Alpha") < names.indexOf("Sweat"));
    }

    @Test
    void rejectsInvalidRankInput() {
        final RankService ranks = new RankService(folder, warnings::add);

        assertThrows(IllegalArgumentException.class,
                () -> ranks.create("Bad.Name", 1));
        assertThrows(IllegalArgumentException.class,
                () -> ranks.create("Bad", -1));
        // Protected weights (staff and core donor weights)
        assertThrows(IllegalArgumentException.class,
                () -> ranks.create("Bad", 35));  // Custom weight
        assertThrows(IllegalArgumentException.class,
                () -> ranks.create("Bad", 38));  // Investor weight
        assertThrows(IllegalArgumentException.class,
                () -> ranks.create("Bad", 40));  // Admin weight
    }

    @Test
    void coreRanksCannotBeDeleted() {
        final RankService ranks = new RankService(folder, warnings::add);

        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> ranks.delete("Default"));

        assertEquals("Core system ranks cannot be deleted.", error.getMessage());
    }

    @Test
    void assignmentsUseUuidsAndSurviveReloads() {
        final RankService ranks = new RankService(folder, warnings::add);
        ranks.create("&6Moderator", 25);
        final UUID id = UUID.randomUUID();

        assertTrue(ranks.assign(id, "Alex", "moderator"));
        assertEquals("Moderator", ranks.rankOf(id).name());
        assertTrue(ranks.knownPlayer("alex").isPresent());
        assertEquals(id, ranks.knownPlayer("alex").orElseThrow().id());

        final RankService reloaded = new RankService(folder, warnings::add);

        assertEquals("Moderator", reloaded.rankOf(id).name());
        assertEquals("Alex", reloaded.knownPlayer("ALEX").orElseThrow().name());
        assertFalse(reloaded.assign(id, "Alex", "Moderator"));
    }

    @Test
    void defaultAssignmentsArePersistedAndSurviveReloads() {
        final RankService ranks = new RankService(folder, warnings::add);
        final UUID id = UUID.randomUUID();

        assertTrue(ranks.assign(id, "Alex", "Default"));
        assertEquals(1, ranks.assignedCount(ranks.defaultRank()));

        final RankService reloaded = new RankService(folder, warnings::add);

        assertEquals("Default", reloaded.rankOf(id).name());
        assertEquals("Alex", reloaded.knownPlayer("alex").orElseThrow().name());
        assertEquals(1, reloaded.assignedCount(reloaded.defaultRank()));
        assertFalse(reloaded.assign(id, "Alex", "Default"));
    }

    @Test
    void missingRanksAreRepairedToStoredDefaultAssignments() {
        final RankService ranks = new RankService(folder, warnings::add);
        ranks.create("&6Moderator", 25);
        final UUID id = UUID.randomUUID();
        ranks.assign(id, "Alex", "Moderator");

        final RankStore store = new RankStore(folder.resolve("ranks.yml"));
        store.load(warnings::add);
        store.removeRank("moderator");
        store.save(warnings::add);

        final RankService reloaded = new RankService(folder, warnings::add);

        assertEquals("Default", reloaded.rankOf(id).name());
        assertEquals(1, reloaded.assignedCount(reloaded.defaultRank()));
        assertEquals("Alex", reloaded.knownPlayer("alex").orElseThrow().name());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("no longer exists")));

        final RankService reloadedAgain = new RankService(folder, warnings::add);
        assertEquals(1, reloadedAgain.assignedCount(reloadedAgain.defaultRank()));
        assertEquals("Default", reloadedAgain.rankOf(id).name());
    }

    @Test
    void deletingACustomRankReassignsItsPlayersToDefault() {
        final RankService ranks = new RankService(folder, warnings::add);
        ranks.create("&6Moderator", 25);
        final UUID id = UUID.randomUUID();
        ranks.assign(id, "Alex", "Moderator");

        ranks.delete("moderator");

        assertEquals("Default", ranks.rankOf(id).name());
        assertTrue(ranks.find("moderator").isEmpty());

        final RankService reloaded = new RankService(folder, warnings::add);
        assertEquals("Default", reloaded.rankOf(id).name());
        assertEquals(1, reloaded.assignedCount(reloaded.defaultRank()));
        assertEquals("Alex", reloaded.knownPlayer("alex").orElseThrow().name());
        assertTrue(reloaded.find("moderator").isEmpty());
    }

    @Test
    void customRanksSurviveReconnects() {
        final UUID id = UUID.randomUUID();
        try (RankService ranks = new RankService(folder, warnings::add)) {
            ranks.create("&6Moderator", 25);
            ranks.assign(id, "Alex", "Moderator");

            assertFalse(ranks.ensureAssigned(id, "Alex"));
        }

        try (RankService reloaded = new RankService(folder, warnings::add)) {
            assertEquals("Moderator", reloaded.rankOf(id).name());
            assertEquals("Alex", reloaded.knownPlayer("alex").orElseThrow().name());
        }
    }

    @Test
    void repeatedJoinsDoNotRewriteUnchangedAssignments() throws IOException {
        final Path file = folder.resolve("ranks.yml");
        try (RankService ranks = new RankService(folder, warnings::add)) {
            final UUID id = UUID.randomUUID();
            assertTrue(ranks.ensureAssigned(id, "Alex"));
            ranks.flush();

            Files.writeString(file, "# unchanged\n");
            assertFalse(ranks.ensureAssigned(id, "Alex"));
            ranks.flush();

            assertEquals("# unchanged\n", Files.readString(file));
        }
    }

    @Test
    void pendingAssignmentsAreFlushedWhenTheServiceCloses() {
        final UUID id = UUID.randomUUID();
        final RankService ranks = new RankService(folder, warnings::add);
        assertTrue(ranks.ensureAssigned(id, "Alex"));
        ranks.close();

        try (RankService reloaded = new RankService(folder, warnings::add)) {
            assertEquals("Default", reloaded.rankOf(id).name());
            assertEquals("Alex", reloaded.knownPlayer("alex").orElseThrow().name());
        }
    }

    @Test
    void multiplePendingAssignmentsAreCoalescedAndFlushedInOrder() {
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final RankService ranks = new RankService(folder, warnings::add);
        ranks.ensureAssigned(first, "First");
        ranks.ensureAssigned(second, "Second");
        ranks.close();

        try (RankService reloaded = new RankService(folder, warnings::add)) {
            assertEquals("Default", reloaded.rankOf(first).name());
            assertEquals("Default", reloaded.rankOf(second).name());
            assertEquals(2, reloaded.assignedCount(reloaded.defaultRank()));
        }
    }

    @Test
    void failedWritesKeepDirtyStateForTheNextFlush() throws IOException {
        final Path data = folder.resolve("ranks.yml");
        final Path temp = folder.resolve("ranks.yml.tmp");
        final UUID id = UUID.randomUUID();

        try (RankService ranks = new RankService(folder, warnings::add)) {
            Files.delete(data);
            Files.createDirectory(temp);
            Files.writeString(temp.resolve("blocker"), "x");

            ranks.assign(id, "Alex", "Default");
            assertTrue(warnings.stream().anyMatch(message -> message.contains("Could not save")));

            Files.delete(temp.resolve("blocker"));
            Files.delete(temp);
            assertTrue(ranks.flush());
        }

        try (RankService reloaded = new RankService(folder, warnings::add)) {
            assertEquals("Default", reloaded.rankOf(id).name());
        }
    }

    @Test
    void reloadDoesNotDuplicateChangeListeners() {
        try (RankService ranks = new RankService(folder, warnings::add)) {
            final AtomicInteger changes = new AtomicInteger();
            ranks.addChangeListener(id -> changes.incrementAndGet());
            final UUID id = UUID.randomUUID();

            ranks.assign(id, "Alex", "Default");
            ranks.reload();
            ranks.assign(id, "Alex", "Admin");

            assertEquals(2, changes.get());
        }
    }

    @Test
    void rankListChangesNotifyOnlyForStructuralEdits() {
        try (RankService ranks = new RankService(folder, warnings::add)) {
            final AtomicInteger refreshes = new AtomicInteger();
            ranks.addRankListListener(refreshes::incrementAndGet);
            final UUID id = UUID.randomUUID();

            ranks.create("&6Moderator", 25);
            assertEquals(1, refreshes.get());

            ranks.assign(id, "Alex", "Moderator");
            assertEquals(1, refreshes.get());

            ranks.delete("moderator");
            assertEquals(2, refreshes.get());
        }
    }
}