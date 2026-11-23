package io.github.near.sweats.currency;

import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.format.NamedTextColor;

/** The two server currencies. The config key is also the command argument spelling. */
public enum CurrencyType {

    SWEAT_COINS("sweatcoins", "Sweat coins", NamedTextColor.YELLOW),
    TRYHARD_SHARDS("tryhardshards", "TryHard Shards", NamedTextColor.LIGHT_PURPLE);

    private final String key;
    private final String displayName;
    private final NamedTextColor color;

    CurrencyType(final String key, final String displayName, final NamedTextColor color) {
        this.key = key;
        this.displayName = displayName;
        this.color = color;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public NamedTextColor color() {
        return color;
    }

    /**
     * Parses a user-supplied currency name. Matching is case-insensitive and also accepts the
     * display name ("Sweat Coins", "TryHard Shards") and the short aliases coins/shards.
     */
    public static Optional<CurrencyType> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace(" ", "");
        return switch (normalized) {
            case "sweatcoins", "sweatcoin", "coins", "coin" -> Optional.of(SWEAT_COINS);
            case "tryhardshards", "tryhardshard", "shards", "shard" -> Optional.of(TRYHARD_SHARDS);
            default -> Optional.empty();
        };
    }
}
