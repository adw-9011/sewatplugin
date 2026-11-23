package io.github.near.sweats.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.chat.OnlinePlayerNames;
import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.vanish.VanishService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TpaServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private RankService ranks;
    private VanishService vanish;

    @BeforeEach
    void setUp() {
        ranks = new RankService(folder, warnings::add);
        vanish = new VanishService(
                TestPlugins.plugin(), ranks, id -> true, new OnlinePlayerNames());
    }

    private TpaService service(final int cooldown, final int expire) {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("tpa.cooldown-seconds", cooldown);
        config.set("tpa.expire-seconds", expire);
        return new TpaService(TestPlugins.plugin(config), ranks, id -> true, vanish);
    }

    @Test
    void settingsAreReadFromConfigAndClamped() {
        final TpaService standard = service(30, 60);
        assertEquals(60, standard.expireSeconds());
        standard.close();

        final TpaService clamped = service(-5, 0);
        assertEquals(1, clamped.expireSeconds());
        clamped.close();

        final TpaService defaults = new TpaService(
                TestPlugins.plugin(), ranks, id -> true, vanish);
        assertEquals(60, defaults.expireSeconds());
        defaults.close();
    }

    @Test
    void requestingYourselfIsRejectedBeforeAnyStateChanges() {
        final TpaService tpa = service(30, 60);
        final List<Component> messages = new ArrayList<>();
        final Player alice = TestCommandSenders.seer("Alice", messages);

        assertThrows(IllegalArgumentException.class, () -> tpa.request(alice, alice, false));
        tpa.close();
    }

    @Test
    void loginStateIsCheckedForBothPlayers() {
        final List<Component> messages = new ArrayList<>();
        final Player alice = TestCommandSenders.seer("Alice", messages);
        final Player bob = TestCommandSenders.seer("Bob", messages);

        final TpaService requesterLocked =
                new TpaService(TestPlugins.plugin(), ranks, id -> false, vanish);
        assertThrows(IllegalArgumentException.class,
                () -> requesterLocked.request(alice, bob, false));
        requesterLocked.close();

        final TpaService targetLocked = new TpaService(
                TestPlugins.plugin(), ranks, id -> !id.equals(bob.getUniqueId()), vanish);
        assertThrows(IllegalArgumentException.class,
                () -> targetLocked.request(alice, bob, false));
        targetLocked.close();
    }

    @Test
    void aTargetThatCannotBeSeenIsTreatedAsOffline() {
        final TpaService tpa = service(30, 60);
        final List<Component> messages = new ArrayList<>();
        final Player alice = TestCommandSenders.player("Alice", 0, true, messages);
        final Player hidden = TestCommandSenders.seer("Hidden", messages);

        assertThrows(IllegalArgumentException.class, () -> tpa.request(alice, hidden, false));
        tpa.close();
    }

    @Test
    void requestMessagesNameBothPlayersAndOfferClickableActions() {
        final TpaService tpa = service(30, 60);
        final List<Component> targetMessages = new ArrayList<>();
        final List<Component> requesterMessages = new ArrayList<>();
        final Player alice = TestCommandSenders.seer("Alice", requesterMessages);
        final Player bob = TestCommandSenders.seer("Bob", targetMessages);

        tpa.request(alice, bob, false);

        assertEquals(1, targetMessages.size());
        final String targetText = TestCommandSenders.plain(targetMessages.get(0));
        assertTrue(targetText.contains("Alice wants to teleport to you."));
        assertTrue(targetText.contains("[ACCEPT]"));
        assertTrue(targetText.contains("[DENY]"));
        assertEquals(1, requesterMessages.size());
        assertTrue(TestCommandSenders.plain(requesterMessages.get(0))
                .contains("Teleport request sent to Bob"));
        tpa.close();
    }

    @Test
    void onlyOneOutgoingRequestIsAllowedAtATime() {
        final TpaService tpa = service(30, 60);
        final List<Component> messages = new ArrayList<>();
        final Player alice = TestCommandSenders.seer("Alice", messages);
        final Player bob = TestCommandSenders.seer("Bob", messages);
        final Player carol = TestCommandSenders.seer("Carol", messages);

        tpa.request(alice, bob, false);

        assertThrows(IllegalArgumentException.class, () -> tpa.request(alice, bob, false));
        assertThrows(IllegalArgumentException.class, () -> tpa.request(alice, carol, false));
        tpa.close();
    }

    @Test
    void acceptingWithoutARequestIsRejectedBeforeTouchingTheServer() {
        final TpaService tpa = service(30, 60);
        final List<Component> messages = new ArrayList<>();
        final Player alice = TestCommandSenders.seer("Alice", messages);

        assertThrows(IllegalArgumentException.class, () -> tpa.accept(alice, null));
        assertThrows(IllegalArgumentException.class, () -> tpa.deny(alice, null));
        assertThrows(IllegalArgumentException.class, () -> tpa.cancel(alice));
        tpa.close();
    }

    @Test
    void loginStateIsCheckedForAcceptDenyAndCancel() {
        final List<Component> messages = new ArrayList<>();
        final Player alice = TestCommandSenders.seer("Alice", messages);
        final TpaService locked = new TpaService(
                TestPlugins.plugin(), ranks, id -> false, vanish);

        assertThrows(IllegalArgumentException.class, () -> locked.accept(alice, null));
        assertThrows(IllegalArgumentException.class, () -> locked.deny(alice, null));
        assertThrows(IllegalArgumentException.class, () -> locked.cancel(alice));
        locked.close();
    }

    @Test
    void incomingNamesAreEmptyWithoutAnyRequests() {
        final TpaService tpa = service(30, 60);

        assertTrue(tpa.incomingNames(UUID.randomUUID(), "").isEmpty());
        tpa.close();
    }
}
