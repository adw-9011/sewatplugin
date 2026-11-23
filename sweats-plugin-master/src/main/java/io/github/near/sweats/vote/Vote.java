package io.github.near.sweats.vote;

import java.util.Locale;

/**
 * A single vote delivered by a Votifier-compatible server list.
 *
 * @param service   the voting site (for example {@code Minecraft.Buzz})
 * @param username  the name the voter typed
 * @param address   the voter's IP address as reported by the site
 * @param timestamp the timestamp reported by the site (seconds for v1, milliseconds for v2)
 * @param protocol  the Votifier protocol version that carried the vote
 * @param uuid      the player UUID when the site supplied one, otherwise empty
 */
public record Vote(
        String service,
        String username,
        String address,
        long timestamp,
        int protocol,
        String uuid) {

    public Vote {
        service = service == null ? "" : service;
        username = username == null ? "" : username;
        address = address == null ? "" : address;
        uuid = uuid == null ? "" : uuid;
    }

    /** Identity used to avoid handing out a reward twice when a site retries a delivery. */
    public String dedupeKey() {
        return protocol + "|" + service + '|'
                + username.toLowerCase(Locale.ROOT) + '|' + timestamp;
    }
}
