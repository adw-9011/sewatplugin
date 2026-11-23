package io.github.near.sweats.auth;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Allow-list of proxy addresses whose forwarded UUID may be trusted in PROXY mode.
 *
 * <p>A backend server cannot see which hop added the UUID to the handshake; it only sees the
 * socket that delivered it. The only safe check available to a plugin is therefore "did this
 * connection come from the address I expect my proxy to use?". Entries are literal IPv4/IPv6
 * addresses or CIDR blocks, and anything that cannot be parsed is ignored (with a warning)
 * instead of being treated as a wildcard. An empty list trusts nobody, so a server that forgets
 * to configure its proxy fails closed rather than accepting forged UUIDs from the internet.
 */
final class ProxyTrust {

    private final List<Subnet> subnets;

    private ProxyTrust(final List<Subnet> subnets) {
        this.subnets = List.copyOf(subnets);
    }

    static ProxyTrust from(final List<String> entries, final Consumer<String> warn) {
        final List<Subnet> parsed = new ArrayList<>();
        if (entries != null) {
            for (final String raw : entries) {
                final String entry = raw == null ? "" : raw.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                try {
                    parsed.add(Subnet.parse(entry));
                } catch (final IllegalArgumentException exception) {
                    warn.accept("Ignoring malformed auth.premium.trusted-proxies entry '"
                            + entry + "': " + exception.getMessage());
                }
            }
        }
        return new ProxyTrust(parsed);
    }

    boolean isEmpty() {
        return subnets.isEmpty();
    }

    /** A missing address is never trusted. */
    boolean trusts(final InetAddress address) {
        if (address == null) {
            return false;
        }
        for (final Subnet subnet : subnets) {
            if (subnet.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private record Subnet(byte[] network, int prefix) {

        static Subnet parse(final String value) {
            final int slash = value.indexOf('/');
            final String addressPart = slash < 0 ? value : value.substring(0, slash);
            int prefix = -1;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(value.substring(slash + 1).trim());
                } catch (final NumberFormatException exception) {
                    throw new IllegalArgumentException("the prefix is not a number");
                }
            }

            final InetAddress address;
            try {
                // ofLiteral never performs a DNS lookup, so a hostname cannot sneak into the
                // allow-list and resolve differently between starts.
                address = InetAddress.ofLiteral(addressPart.trim());
            } catch (final IllegalArgumentException exception) {
                throw new IllegalArgumentException("not a literal IP address");
            }

            final byte[] bytes = address.getAddress();
            final int maximum = bytes.length * 8;
            if (prefix < 0) {
                prefix = maximum;
            }
            if (prefix > maximum) {
                throw new IllegalArgumentException(
                        "the prefix must be between 0 and " + maximum);
            }
            return new Subnet(bytes, prefix);
        }

        boolean contains(final InetAddress address) {
            final byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            final int fullBytes = prefix / 8;
            final int remainingBits = prefix % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            final int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
