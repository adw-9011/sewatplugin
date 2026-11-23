package io.github.near.sweats.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeaderboardStoreTest {

    private static final Consumer<String> IGNORE = message -> {};

    @TempDir
    Path folder;

    @Test
    void savesPositionsAndReloadsThem() {
        final Path file = folder.resolve("leaderboards.yml");
        final LeaderboardStore store = new LeaderboardStore(file, IGNORE);
        store.load();

        final LeaderboardStore.StoredDisplay first =
                store.add(new Location(world("world"), 10.5D, 64.0D, -3.5D));
        store.add(new Location(world("world_nether"), 0.0D, 100.0D, 0.0D));

        final LeaderboardStore reloaded = new LeaderboardStore(file, IGNORE);
        reloaded.load();

        assertEquals(2, reloaded.all().size());
        final LeaderboardStore.StoredDisplay loaded = reloaded.all().stream()
                .filter(display -> display.id().equals(first.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("world", loaded.world());
        assertEquals(10.5D, loaded.x());
        assertEquals(64.0D, loaded.y());
        assertEquals(-3.5D, loaded.z());
        assertEquals(LeaderboardCategory.PLAYTIME, loaded.category());
    }

    @Test
    void savesTheCategoryOfEachBoard() {
        final Path file = folder.resolve("leaderboards.yml");
        final LeaderboardStore store = new LeaderboardStore(file, IGNORE);
        store.load();
        final LeaderboardStore.StoredDisplay board = store.add(
                new Location(world("world"), 0.0D, 64.0D, 0.0D), LeaderboardCategory.VOTES);

        final LeaderboardStore reloaded = new LeaderboardStore(file, IGNORE);
        reloaded.load();

        assertEquals(LeaderboardCategory.VOTES, reloaded.all().getFirst().category());
        assertEquals(board.id(), reloaded.all().getFirst().id());
    }

    @Test
    void oldBoardsWithoutACategoryBecomePlaytimeBoards() throws IOException {
        final Path file = folder.resolve("leaderboards.yml");
        Files.writeString(file, """
                displays:
                  770cf9be-2a72-3025-b3b4-a7e1698c4d08:
                    world: world
                    x: 0.0
                    y: 64.0
                    z: 0.0
                """);

        final LeaderboardStore store = new LeaderboardStore(file, IGNORE);
        store.load();

        assertEquals(LeaderboardCategory.PLAYTIME, store.all().getFirst().category());
    }

    @Test
    void findsTheNearestDisplayInTheSameWorld() {
        final LeaderboardStore store = new LeaderboardStore(folder.resolve("leaderboards.yml"), IGNORE);
        store.load();

        final LeaderboardStore.StoredDisplay near =
                store.add(new Location(world("world"), 0.0D, 64.0D, 0.0D));
        store.add(new Location(world("world"), 100.0D, 64.0D, 0.0D));
        store.add(new Location(world("other"), 1.0D, 64.0D, 1.0D));

        final Optional<LeaderboardStore.StoredDisplay> found =
                store.nearest(new Location(world("world"), 3.0D, 64.0D, 4.0D), 8.0D);

        assertTrue(found.isPresent());
        assertSame(near, found.get());
    }

    @Test
    void returnsNothingWhenEveryDisplayIsOutOfRange() {
        final LeaderboardStore store = new LeaderboardStore(folder.resolve("leaderboards.yml"), IGNORE);
        store.load();
        store.add(new Location(world("world"), 0.0D, 64.0D, 0.0D));

        assertTrue(store.nearest(new Location(world("world"), 50.0D, 64.0D, 0.0D), 8.0D)
                .isEmpty());
        assertTrue(store.nearest(new Location(world("other"), 0.0D, 64.0D, 0.0D), 8.0D)
                .isEmpty());
    }

    @Test
    void removesOnlyAnExistingDisplay() {
        final LeaderboardStore store = new LeaderboardStore(folder.resolve("leaderboards.yml"), IGNORE);
        store.load();
        final LeaderboardStore.StoredDisplay stored =
                store.add(new Location(world("world"), 0.0D, 64.0D, 0.0D));

        assertFalse(store.remove(java.util.UUID.randomUUID()));
        assertTrue(store.remove(stored.id()));
        assertTrue(store.all().isEmpty());
    }

    @Test
    void aFailedSaveRollsBackPlaceAndRemove() throws IOException {
        final Path file = folder.resolve("leaderboards.yml");
        final List<String> warnings = new ArrayList<>();
        final LeaderboardStore store = new LeaderboardStore(file, warnings::add);
        store.load();

        final LeaderboardStore.StoredDisplay stored =
                store.add(new Location(world("world"), 0.0D, 64.0D, 0.0D));

        Files.delete(file);
        Files.createDirectories(file);
        Files.writeString(file.resolve("block"), "not a yaml file");

        assertThrows(IllegalStateException.class,
                () -> store.add(new Location(world("world"), 1.0D, 64.0D, 1.0D)));
        assertEquals(1, store.all().size());
        assertTrue(store.all().getFirst().id().equals(stored.id()));

        assertThrows(IllegalStateException.class, () -> store.remove(stored.id()));
        assertEquals(1, store.all().size());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("leaderboards.yml")));
    }

    private static World world(final String name) {
        return (World) Proxy.newProxyInstance(
                LeaderboardStoreTest.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> name.hashCode();
                    case "toString" -> name;
                    default -> null;
                });
    }
}
