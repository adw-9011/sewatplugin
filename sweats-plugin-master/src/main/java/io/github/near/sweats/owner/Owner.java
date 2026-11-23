package io.github.near.sweats.owner;

import java.util.UUID;

/**
 * The hardcoded server owner.
 *
 * <p>This identity is treated as the highest authority by Sweats: every plugin permission is
 * granted and this plugin's punishment/maintenance systems refuse to target it. The console,
 * the host and other plugins are outside Sweats' control and can still act on this account.
 * Checks are by UUID only, so joining under the owner's name with a different UUID grants nothing.
 */
public final class Owner {

    public static final UUID ID = UUID.fromString("c4f1f6a2-6669-3cab-97b3-de3e0645584b");
    public static final String NAME = "Bhosra";

    private Owner() {}

    public static boolean isOwner(final UUID id) {
        return ID.equals(id);
    }
}
