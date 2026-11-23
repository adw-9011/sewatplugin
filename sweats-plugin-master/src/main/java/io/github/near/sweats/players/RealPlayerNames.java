package io.github.near.sweats.players;

import java.util.regex.Pattern;

/**
 * Checks whether a name can belong to a Minecraft account.
 *
 * <p>This only validates the shape of a name. It intentionally does not try to guess whether a
 * name looks like an NPC: a real player can be called {@code Shopkeeperkisuke}, and a cached
 * profile can be called anything. The {@link RealPlayerRegistry}, seeded from confirmed joins and
 * Sweats accounts, decides which names are actually ranked.
 */
public final class RealPlayerNames {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{2,16}");
    private static final Pattern IP = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RealPlayerNames() {}

    public static boolean valid(final String name) {
        if (name == null) {
            return false;
        }
        final String value = name.trim();
        if (!NAME.matcher(value).matches() || IP.matcher(value).matches()
                || UUID.matcher(value).matches()) {
            return false;
        }
        // Offline-mode servers can have short names such as x3, but a bare two-letter name is
        // much more likely to be a placeholder than a real account.
        return value.length() != 2 || value.chars().anyMatch(Character::isDigit);
    }
}
