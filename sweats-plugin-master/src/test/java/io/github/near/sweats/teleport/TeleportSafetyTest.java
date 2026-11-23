package io.github.near.sweats.teleport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class TeleportSafetyTest {

    private static World world(final String name) {
        return (World) Proxy.newProxyInstance(
                TeleportSafetyTest.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> null;
                });
    }

    @Test
    void blockedWorldsAreCaseInsensitiveAndBlankEntriesAreIgnored() {
        final TeleportSafety safety =
                new TeleportSafety(Set.of("world_nether", "  ", "World_The_End"));

        assertTrue(safety.isBlocked("world_nether"));
        assertTrue(safety.isBlocked("WORLD_NETHER"));
        assertTrue(safety.isBlocked("WORLD_THE_END"));
        assertFalse(safety.isBlocked("world"));
        assertFalse(safety.isBlocked(""));
        assertTrue(safety.isBlocked((String) null));
        assertTrue(safety.isBlocked((World) null));
        assertTrue(safety.isBlocked(world("world_nether")));
        assertFalse(safety.isBlocked(world("world")));
    }

    @Test
    void ofCopiesTheConfiguredList() {
        final TeleportSafety safety = TeleportSafety.of(List.of("spawn"));

        assertTrue(safety.isBlocked("spawn"));
        assertFalse(safety.isBlocked("world"));
    }

    @Test
    void unsafeOrBlockedDestinationsAreRejectedBeforeTouchingTheWorld() {
        final TeleportSafety safety = new TeleportSafety(Set.of("spawn"));

        assertNull(safety.safe(null));
        assertNull(safety.safe(new Location(null, 0, 64, 0)));
        assertNull(safety.safe(new Location(world("spawn"), 0, 64, 0)));
    }
}
