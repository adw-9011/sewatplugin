package io.github.near.sweats.pm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.nick.NickService;
import io.github.near.sweats.perks.PerkService;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagingServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private final List<String> audits = new ArrayList<>();
    private final Map<String, Player> byName = new HashMap<>();
    private final Map<UUID, Player> byId = new HashMap<>();
    private final AtomicLong clock = new AtomicLong(10_000L);

    private RankService ranks;
    private NickService nicks;
    private PerkService perks;
    private PermissionService permissions;
    private MessagingService messages;
    private MessageDirectory directory;

    @BeforeEach
    void setUp() {
        ranks = new RankService(folder, warnings::add);
        nicks = new NickService(folder, warnings::add);
        perks = new PerkService(folder, ranks, warnings::add);
        permissions = new PermissionService(TestPlugins.plugin(), folder, ranks, warnings::add);
        directory = new MessageDirectory() {
            @Override
            public Optional<Player> findVisible(final CommandSender viewer, final String name) {
                return Optional.ofNullable(byName.get(name.toLowerCase()));
            }

            @Override
            public Optional<Player> online(final UUID id) {
                return Optional.ofNullable(byId.get(id));
            }
        };
        messages = new MessagingService(
                null, ranks, nicks, perks, directory, permissions, id -> true,
                (from, to, content) -> audits.add(from + ">" + to + ":" + content),
                warnings::add, clock::get, 40, 1_000L);
    }

    @Test
    void deliversBothSidesAndUpdatesTheReplyTarget() {
        final List<Component> alex = new ArrayList<>();
        final List<Component> bob = new ArrayList<>();
        final Player sender = register("Alex", alex, NamedTextColor.BLUE);
        final Player target = register("Bob", bob, NamedTextColor.GOLD);

        final MessagingService.Outcome outcome = messages.send(sender, "bob", "hello there");

        assertEquals(MessagingService.Result.SENT, outcome.result());
        assertEquals(1, alex.size());
        assertEquals(1, bob.size());
        // Sender sees: "You sent a message to [Custom] Bob: hello there"
        assertEquals("You sent a message to [Custom] Bob: hello there", TestCommandSenders.plain(alex.get(0)));
        // Receiver sees: "[Sweat] Alex sent a message to you: hello there"
        assertEquals("[Sweat] Alex sent a message to you: hello there", TestCommandSenders.plain(bob.get(0)));
        assertEquals(NamedTextColor.WHITE,
                TestCommandSenders.colorOf(alex.get(0), "hello there"));
        assertEquals(NamedTextColor.LIGHT_PURPLE,
                TestCommandSenders.colorOf(alex.get(0), "You sent a message to "));
        assertEquals(NamedTextColor.GOLD,
                TestCommandSenders.colorOf(alex.get(0), "Bob"));
        assertEquals(Optional.of(target.getUniqueId()), messages.replyTarget(sender.getUniqueId()));
        assertEquals(List.of("Alex>Bob:hello there"), audits);
    }

    @Test
    void failedSendLeavesThePreviousReplyTargetAlone() {
        final Player sender = register("Alex", new ArrayList<>(), NamedTextColor.BLUE);
        final Player bob = register("Bob", new ArrayList<>(), NamedTextColor.GOLD);

        assertEquals(MessagingService.Result.SENT,
                messages.send(sender, "Bob", "first").result());

        assertEquals(MessagingService.Result.UNKNOWN,
                messages.send(sender, "Ghost", "second").result());
        assertEquals(Optional.of(bob.getUniqueId()), messages.replyTarget(sender.getUniqueId()));

        assertEquals(MessagingService.Result.EMPTY,
                messages.send(sender, "Bob", "   ").result());
        assertEquals(Optional.of(bob.getUniqueId()), messages.replyTarget(sender.getUniqueId()));
    }

    @Test
    void selfEmptyAndOversizedMessagesAreRejected() {
        final Player sender = register("Alex", new ArrayList<>(), NamedTextColor.BLUE);
        final Player bob = register("Bob", new ArrayList<>(), NamedTextColor.GOLD);

        assertEquals(MessagingService.Result.SELF,
                messages.send(sender, "Alex", "hi").result());
        assertEquals(MessagingService.Result.EMPTY,
                messages.send(sender, "Bob", "").result());
        assertEquals(MessagingService.Result.TOO_LONG,
                messages.send(sender, "Bob", "x".repeat(41)).result());
        assertTrue(messages.replyTarget(sender.getUniqueId()).isEmpty());
        assertTrue(messages.replyTarget(bob.getUniqueId()).isEmpty());
    }

    @Test
    void cooldownIsConfigurableAndOnlyStartsAfterDelivery() {
        final Player sender = register("Alex", new ArrayList<>(), NamedTextColor.BLUE);
        register("Bob", new ArrayList<>(), NamedTextColor.GOLD);

        assertEquals(MessagingService.Result.SENT,
                messages.send(sender, "Bob", "one").result());

        clock.set(10_500L);
        final MessagingService.Outcome blocked = messages.send(sender, "Bob", "two");
        assertEquals(MessagingService.Result.COOLDOWN, blocked.result());
        assertEquals(1L, blocked.cooldownRemainingSeconds());

        clock.set(11_000L);
        assertEquals(MessagingService.Result.SENT,
                messages.send(sender, "Bob", "three").result());
    }

    @Test
    void bypassPermissionSkipsTheCooldown() {
        final List<Component> out = new ArrayList<>();
        final Player sender = TestCommandSenders.seer("Alex", out);
        registerPlayer(sender, NamedTextColor.BLUE);
        register("Bob", new ArrayList<>(), NamedTextColor.GOLD);

        assertEquals(MessagingService.Result.SENT,
                messages.send(sender, "Bob", "one").result());
        assertEquals(MessagingService.Result.SENT,
                messages.send(sender, "Bob", "two").result());
    }

    @Test
    void replyUsesTheSessionTargetAndLogoutClearsIt() {
        final List<Component> alexMessages = new ArrayList<>();
        final List<Component> bobMessages = new ArrayList<>();
        final Player alex = register("Alex", alexMessages, NamedTextColor.BLUE);
        final Player bob = register("Bob", bobMessages, NamedTextColor.GOLD);

        messages.send(alex, "Bob", "ping");
        clock.addAndGet(1_000L);
        assertEquals(MessagingService.Result.SENT,
                messages.reply(alex, "pong").result());
        // Sender sees: "You sent a message to [Custom] Bob: pong"
        assertEquals("You sent a message to [Custom] Bob: pong", TestCommandSenders.plain(alexMessages.get(1)));
        // Receiver sees: "[Sweat] Alex replied to your message: pong"
        assertEquals("[Sweat] Alex replied to your message: pong", TestCommandSenders.plain(bobMessages.get(1)));

        assertEquals(Optional.of(alex.getUniqueId()), messages.replyTarget(bob.getUniqueId()));

        messages.clear(alex.getUniqueId());
        assertEquals(MessagingService.Result.NO_REPLY,
                messages.reply(alex, "again").result());
        assertNull(messages.replyTarget(alex.getUniqueId()).orElse(null));
        assertEquals(Optional.of(alex.getUniqueId()), messages.replyTarget(bob.getUniqueId()));
    }

    @Test
    void vanishedAndUnauthenticatedTargetsAreUnavailable() {
        final Player sender = TestCommandSenders.blindPlayer("Alex", new ArrayList<>());
        registerPlayer(sender, NamedTextColor.BLUE);
        final Player hidden = TestCommandSenders.player("Hidden", 0, false, new ArrayList<>());
        registerPlayer(hidden, NamedTextColor.GRAY);

        assertEquals(MessagingService.Result.HIDDEN,
                messages.send(sender, "Hidden", "hello").result());
        assertTrue(messages.replyTarget(sender.getUniqueId()).isEmpty());
        assertTrue(messages.replyTarget(hidden.getUniqueId()).isEmpty());
    }

    @Test
    void anUnauthenticatedTargetIsRejected() {
        final Player sender = register("Alex", new ArrayList<>(), NamedTextColor.BLUE);
        final Player bob = register("Bob", new ArrayList<>(), NamedTextColor.GOLD);
        messages = new MessagingService(
                null, ranks, nicks, perks, directory, permissions,
                id -> !id.equals(bob.getUniqueId()),
                (from, to, content) -> audits.add(from + ">" + to + ":" + content),
                warnings::add, clock::get, 40, 1_000L);

        assertEquals(MessagingService.Result.OFFLINE,
                messages.send(sender, "Bob", "hello").result());
        assertEquals(MessagingService.Result.NO_REPLY,
                messages.reply(sender, "hello").result());
        assertTrue(messages.replyTarget(sender.getUniqueId()).isEmpty());
    }

    @Test
    void formattingInjectionStaysLiteral() {
        final List<Component> out = new ArrayList<>();
        final Player sender = register("Alex", out, NamedTextColor.BLUE);
        register("Bob", new ArrayList<>(), NamedTextColor.GOLD);

        messages.send(sender, "Bob", "&cred \u00a74also not red");

        final String plain = TestCommandSenders.plain(out.get(0));
        assertTrue(plain.contains("&cred"));
        assertTrue(plain.contains("\u00a74also not red"));
    }

    @Test
    void controlCharactersAreRemoved() {
        final List<Component> out = new ArrayList<>();
        final Player sender = register("Alex", out, NamedTextColor.BLUE);
        register("Bob", new ArrayList<>(), NamedTextColor.GOLD);

        messages.send(sender, "Bob", "hello\u0000\nworld");

        final String plain = TestCommandSenders.plain(out.get(0));
        assertFalse(plain.contains("\u0000"));
        assertTrue(plain.contains("hello world"));
    }

    private Player register(
            final String name, final List<Component> out, final NamedTextColor color) {
        final Player player = TestCommandSenders.visiblePlayer(name, out);
        registerPlayer(player, color);
        return player;
    }

    private void registerPlayer(final Player player, final NamedTextColor color) {
        byName.put(player.getName().toLowerCase(), player);
        byId.put(player.getUniqueId(), player);
        final String rankName;
        if (NamedTextColor.BLUE.equals(color)) {
            rankName = "Sweat";
        } else if (NamedTextColor.GOLD.equals(color)) {
            rankName = "Custom";
        } else {
            rankName = "Default";
        }
        ranks.assign(player.getUniqueId(), player.getName(), rankName);
    }
}
