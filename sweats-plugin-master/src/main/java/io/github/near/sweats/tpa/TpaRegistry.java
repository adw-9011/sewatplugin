package io.github.near.sweats.tpa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Pure teleport-request state: at most one outgoing request per player, any number of incoming
 * requests per target, and one cooldown per requester.
 *
 * <p>Every method takes the current time so expiry and cooldown behaviour can be tested without
 * sleeping, and every access first removes expired entries so the maps stay bounded.
 */
public final class TpaRegistry {

    public record Request(UUID requester, UUID target, long createdAt, long expiresAt) {

        public boolean expired(final long now) {
            return now >= expiresAt;
        }

        public int secondsRemaining(final long now) {
            final long remaining = expiresAt - now;
            if (remaining <= 0L) {
                return 0;
            }
            return (int) ((remaining + 999L) / 1000L);
        }
    }

    private final Map<UUID, Request> outgoing = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldowns = new LinkedHashMap<>();

    /** Adds a request. Callers check for an existing outgoing request first. */
    public Request put(final UUID requester, final UUID target, final long now, final long ttlMillis) {
        final Request request = new Request(
                requester, target, now, now + Math.max(1L, ttlMillis));
        outgoing.put(requester, request);
        return request;
    }

    public Optional<Request> outgoing(final UUID requester, final long now) {
        purge(now);
        return Optional.ofNullable(outgoing.get(requester));
    }

    /** Newest first, so {@code /tpaccept} without a name uses the most recent request. */
    public List<Request> incoming(final UUID target, final long now) {
        purge(now);
        final List<Request> found = new ArrayList<>();
        for (final Request request : outgoing.values()) {
            if (request.target().equals(target)) {
                found.add(request);
            }
        }
        found.sort(Comparator.comparingLong(Request::createdAt).reversed());
        return List.copyOf(found);
    }

    /** Removes and returns a request only when it matches both players. */
    public Optional<Request> take(final UUID requester, final UUID target, final long now) {
        purge(now);
        final Request request = outgoing.get(requester);
        if (request == null || !request.target().equals(target)) {
            return Optional.empty();
        }
        outgoing.remove(requester);
        return Optional.of(request);
    }

    public Optional<Request> cancel(final UUID requester, final long now) {
        purge(now);
        return Optional.ofNullable(outgoing.remove(requester));
    }

    /** Removes requests in both directions, e.g. on disconnect. Cooldowns are kept. */
    public void removePlayer(final UUID id) {
        outgoing.entrySet().removeIf(entry ->
                entry.getKey().equals(id) || entry.getValue().target().equals(id));
    }

    public long cooldownRemaining(final UUID requester, final long now) {
        purge(now);
        final Long until = cooldowns.get(requester);
        if (until == null || until <= now) {
            return 0L;
        }
        return until - now;
    }

    public void startCooldown(final UUID requester, final long now, final long millis) {
        if (millis <= 0L) {
            cooldowns.remove(requester);
            return;
        }
        cooldowns.put(requester, now + millis);
    }

    public void purge(final long now) {
        outgoing.entrySet().removeIf(entry -> entry.getValue().expired(now));
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    public int pending() {
        return outgoing.size();
    }

    public void clear() {
        outgoing.clear();
        cooldowns.clear();
    }

    public OptionalLong cooldownUntil(final UUID requester) {
        final Long until = cooldowns.get(requester);
        return until == null ? OptionalLong.empty() : OptionalLong.of(until);
    }
}
