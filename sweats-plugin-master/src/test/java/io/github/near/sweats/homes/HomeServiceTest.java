package io.github.near.sweats.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private RankService ranks;
    private HomeService homes;

    @BeforeEach
    void setUp() {
        ranks = new RankService(folder, warnings::add);
        homes = new HomeService(
                TestPlugins.plugin(), folder, ranks, id -> true, warnings::add);
    }

    @Test
    void rankChangesTheAccessibleSlotCountImmediately() {
        final UUID id = UUID.randomUUID();

        assertEquals(2, homes.limit(id));
        ranks.assign(id, "Alex", "Grinder");
        assertEquals(2, homes.limit(id));
        ranks.assign(id, "Alex", "Sweat");
        assertEquals(3, homes.limit(id));
        ranks.assign(id, "Alex", "Sweat+");
        assertEquals(4, homes.limit(id));
        ranks.assign(id, "Alex", "Custom");
        assertEquals(5, homes.limit(id));
        ranks.assign(id, "Alex", "Investor");
        assertEquals(7, homes.limit(id));
        ranks.assign(id, "Alex", "Default");
        assertEquals(2, homes.limit(id));
    }

    @Test
    void aDowngradeNeverDeletesStoredHomes() {
        final UUID id = UUID.randomUUID();
        final HomeStore store = new HomeStore(folder.resolve("homes.yml"));
        final Home locked = new Home(7, "world", 1, 64, 2, 0, 0);
        store.put(id, locked);
        assertTrue(store.save(warnings::add));

        ranks.assign(id, "Alex", "Investor");
        final HomeService service = new HomeService(
                TestPlugins.plugin(), folder, ranks, ignored -> true, warnings::add);
        assertEquals(7, service.limit(id));
        assertEquals(locked, service.homes(id).get(7));

        ranks.assign(id, "Alex", "Default");
        assertEquals(2, service.limit(id));
        assertEquals(locked, service.homes(id).get(7));

        ranks.assign(id, "Alex", "Investor");
        assertEquals(7, service.limit(id));
        assertEquals(locked, service.homes(id).get(7));
    }

    @Test
    void lockedSlotsAreRejectedWithoutTouchingTheWorld() {
        final List<Component> messages = new ArrayList<>();
        final Player player = TestCommandSenders.player("Alex", 0, true, messages);

        homes.setHome(player, 5);

        assertEquals(1, messages.size());
        assertTrue(TestCommandSenders.plain(messages.get(0)).contains("between 1 and 2"));
        assertTrue(homes.homes(player.getUniqueId()).isEmpty());
    }

    @Test
    void unauthenticatedPlayersCannotOpenOrUseHomes() {
        final List<Component> messages = new ArrayList<>();
        final Player player = TestCommandSenders.player("Alex", 0, true, messages);
        final HomeService locked = new HomeService(
                TestPlugins.plugin(), folder, ranks, id -> false, warnings::add);

        locked.openMenu(player);
        locked.select(player, 1);
        locked.setHome(player, 1);

        assertEquals(3, messages.size());
        assertTrue(messages.stream()
                .allMatch(message -> TestCommandSenders.plain(message).contains("log in first")));
    }

    @Test
    void cancellingAnInactiveWarmupIsHarmless() {
        homes.cancel(UUID.randomUUID(), "Teleport cancelled.");
        homes.cancelAll();
        assertTrue(warnings.isEmpty());
        homes.close();
    }

    @Test
    void blockedWorldsComeFromConfig() {
        final var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("teleport.blocked-worlds", List.of("spawn"));
        final HomeService service = new HomeService(
                TestPlugins.plugin(config), folder, ranks, id -> true, warnings::add);

        assertTrue(service.isBlocked(world("spawn")));
        assertFalse(service.isBlocked(world("world")));
    }

    private static org.bukkit.World world(final String name) {
        return (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
                HomeServiceTest.class.getClassLoader(),
                new Class<?>[] {org.bukkit.World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> null;
                });
    }
}