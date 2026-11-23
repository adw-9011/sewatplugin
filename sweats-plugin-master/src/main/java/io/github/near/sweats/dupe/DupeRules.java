package io.github.near.sweats.dupe;

import java.util.Optional;

/**
 * Pure classification rules. They deliberately separate a material (fungible, duplicates are
 * normal after splitting or merging) from a unique item identity (duplicates are evidence).
 * Confirmed means the state is impossible without an exploit or plugin bug; suspected means it
 * needs a human look.
 */
public final class DupeRules {

    public enum Classification {
        SUSPECTED,
        CONFIRMED
    }

    public record Finding(Classification classification, String code, String detail) {}

    private DupeRules() {}

    /** Validates one stack. Unique items may never stack, regardless of the material limit. */
    public static Optional<Finding> stack(final int amount, final int maxStack, final boolean unique) {
        if (amount <= 0) {
            return Optional.of(new Finding(
                    Classification.CONFIRMED, "invalid-amount",
                    "stack amount is " + amount));
        }
        if (amount > Math.max(1, maxStack)) {
            return Optional.of(new Finding(
                    Classification.CONFIRMED, "overstacked",
                    "amount " + amount + " exceeds max " + maxStack));
        }
        if (unique && amount > 1) {
            return Optional.of(new Finding(
                    Classification.CONFIRMED, "stacked-unique",
                    "unique item amount is " + amount));
        }
        return Optional.empty();
    }

    /** The same unique identity in two places at once. */
    public static Optional<Finding> duplicateIdentity(
            final String identity, final String first, final String second) {
        if (identity == null || identity.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Finding(
                Classification.CONFIRMED, "duplicate-identity",
                "identity " + identity + " at " + first + " and " + second));
    }

    /** A container holding another container is unusual but may be legitimate on some servers. */
    public static Optional<Finding> nestedContainer(final boolean containerInsideContainer) {
        if (!containerInsideContainer) {
            return Optional.empty();
        }
        return Optional.of(new Finding(
                Classification.SUSPECTED, "nested-container",
                "a shulker or bundle contains another container"));
    }

    /** A unique item stored inside a container is fine on its own, but worth recording. */
    public static Optional<Finding> uniqueInContainer(final String identity, final String location) {
        if (identity == null || identity.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Finding(
                Classification.SUSPECTED, "unique-in-container",
                "identity " + identity + " inside " + location));
    }

    public static String fingerprint(
            final String material, final int amount, final String identity) {
        return material + " x" + amount + (identity == null || identity.isBlank()
                ? ""
                : " id=" + identity);
    }
}
