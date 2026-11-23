package io.github.near.sweats.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.tpa.TpaRegistry.Request;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TpaRegistryTest {

    private static final long TTL = 60_000L;

    @Test
    void storesOneOutgoingRequestPerRequester() {
        final TpaRegistry registry = new TpaRegistry();
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final UUID carol = UUID.randomUUID();

        final Request first = registry.put(alice, bob, 1_000L, TTL);
        assertEquals(1_000L, first.createdAt());
        assertEquals(61_000L, first.expiresAt());
        assertTrue(registry.outgoing(alice, 1_000L).isPresent());

        registry.put(alice, carol, 2_000L, TTL);
        assertEquals(carol, registry.outgoing(alice, 2_000L).orElseThrow().target());
        assertEquals(1, registry.pending());
    }

    @Test
    void incomingRequestsAreNewestFirst() {
        final TpaRegistry registry = new TpaRegistry();
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final UUID carol = UUID.randomUUID();

        registry.put(alice, carol, 1_000L, TTL);
        registry.put(bob, carol, 2_000L, TTL);

        final List<Request> incoming = registry.incoming(carol, 2_000L);
        assertEquals(List.of(bob, alice), incoming.stream().map(Request::requester).toList());
        assertTrue(registry.incoming(alice, 2_000L).isEmpty());
    }

    @Test
    void expiryPurgesRequestsAndReportsZeroSeconds() {
        final TpaRegistry registry = new TpaRegistry();
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();

        final Request request = registry.put(alice, bob, 1_000L, 5_000L);
        assertEquals(5, request.secondsRemaining(1_000L));
        assertEquals(1, request.secondsRemaining(5_999L));
        assertFalse(request.expired(5_999L));
        assertTrue(request.expired(6_000L));
        assertEquals(0, request.secondsRemaining(6_000L));

        assertTrue(registry.outgoing(alice, 6_000L).isEmpty());
        assertEquals(0, registry.pending());
        assertTrue(registry.incoming(bob, 6_000L).isEmpty());
    }

    @Test
    void takeOnlyRemovesAMatchingRequesterTargetPair() {
        final TpaRegistry registry = new TpaRegistry();
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final UUID carol = UUID.randomUUID();
        registry.put(alice, bob, 1_000L, TTL);

        assertTrue(registry.take(alice, carol, 1_000L).isEmpty());
        assertTrue(registry.outgoing(alice, 1_000L).isPresent());

        assertEquals(bob, registry.take(alice, bob, 1_000L).orElseThrow().target());
        assertTrue(registry.outgoing(alice, 1_000L).isEmpty());
    }

    @Test
    void cancelOnlyRemovesTheRequestersOwnRequest() {
        final TpaRegistry registry = new TpaRegistry();
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        registry.put(alice, bob, 1_000L, TTL);

        assertEquals(bob, registry.cancel(alice, 1_000L).orElseThrow().target());
        assertTrue(registry.cancel(alice, 1_000L).isEmpty());
    }

    @Test
    void disconnectingRemovesRequestsInBothDirectionsButKeepsCooldowns() {
        final TpaRegistry registry = new TpaRegistry();
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final UUID carol = UUID.randomUUID();
        registry.put(alice, bob, 1_000L, TTL);
        registry.put(bob, carol, 1_000L, TTL);
        registry.startCooldown(alice, 1_000L, 30_000L);

        registry.removePlayer(bob);

        assertTrue(registry.outgoing(alice, 1_000L).isEmpty());
        assertTrue(registry.outgoing(bob, 1_000L).isEmpty());
        assertEquals(30_000L, registry.cooldownRemaining(alice, 1_000L));
    }

    @Test
    void cooldownIsReportedAndPurgedWithoutSleeping() {
        final TpaRegistry registry = new TpaRegistry();
        final UUID alice = UUID.randomUUID();

        registry.startCooldown(alice, 10_000L, 30_000L);
        assertEquals(40_000L, registry.cooldownUntil(alice).orElseThrow());
        assertEquals(30_000L, registry.cooldownRemaining(alice, 10_000L));
        assertEquals(1_000L, registry.cooldownRemaining(alice, 39_000L));
        assertEquals(0L, registry.cooldownRemaining(alice, 40_000L));
        assertTrue(registry.cooldownUntil(alice).isEmpty());

        registry.startCooldown(alice, 50_000L, 0L);
        assertTrue(registry.cooldownUntil(alice).isEmpty());
    }

    @Test
    void clearDropsRequestsAndCooldowns() {
        final TpaRegistry registry = new TpaRegistry();
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        registry.put(alice, bob, 1_000L, TTL);
        registry.startCooldown(alice, 1_000L, 30_000L);

        registry.clear();

        assertEquals(0, registry.pending());
        assertEquals(0L, registry.cooldownRemaining(alice, 1_000L));
    }

    @Test
    void putUsesAtLeastOneMillisecondTtl() {
        final TpaRegistry registry = new TpaRegistry();
        final Request request = registry.put(
                UUID.randomUUID(), UUID.randomUUID(), 1_000L, -50L);

        assertEquals(1_001L, request.expiresAt());
    }
}
