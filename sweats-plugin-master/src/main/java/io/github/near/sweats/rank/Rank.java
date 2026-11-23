package io.github.near.sweats.rank;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

public final class Rank implements Comparable<Rank> {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final String name;
    private final String key;
    private final Component displayName;
    private final int weight;
    private final boolean core;

    public Rank(final String name, final Component displayName, final int weight, final boolean core) {
        this.name = Objects.requireNonNull(name, "name");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.weight = weight;
        this.core = core;
        this.key = key(name);
    }

    /** The raw name (with legacy codes stripped), used for keys and lookups. */
    public String name() {
        return name;
    }

    /** The formatted display name as an Adventure Component. */
    public Component displayName() {
        return displayName;
    }

    /**
     * The unbracketed configured display name, with the Sweat+ plus recolored when a personal
     * color is configured. Other ranks retain their exact configured component.
     */
    public Component displayName(final TextColor plusColor) {
        if (plusColor == null || !"sweat+".equals(key)) {
            return displayName;
        }
        final int plus = name.indexOf('+');
        if (plus < 0) {
            return displayName;
        }
        final TextColor baseColor = primaryColor();
        return Component.text(name.substring(0, plus), baseColor)
                .append(Component.text("+", plusColor))
                .append(Component.text(name.substring(plus + 1), baseColor));
    }

    /** @deprecated Use {@link #displayName()} instead. */
    @Deprecated
    public TextColor color() {
        return primaryColor();
    }

    /** Returns the primary color from the display name, or white if none found. */
    public TextColor primaryColor() {
        return extractFirstColor(displayName).orElse(TextColor.color(0xFFFFFF));
    }

    public int weight() {
        return weight;
    }

    public boolean core() {
        return core;
    }

    public String key() {
        return key;
    }

    public static String key(final String name) {
        return WHITESPACE.matcher(RankFormat.stripCodes(name).trim().toLowerCase(Locale.ROOT).replace('_', ' '))
                .replaceAll(" ");
    }

    public boolean isDefault() {
        return "default".equals(key);
    }

    public Component label() {
        final TextColor baseColor = primaryColor();
        return Component.text("[", baseColor).append(displayName).append(Component.text("]", baseColor));
    }

    /**
     * The rank tag with an optionally recoloured {@code +}. Only the Sweat+ rank has a plus icon;
     * every other character keeps the rank's formatting from displayName.
     */
    public Component label(final TextColor plusColor) {
        if (plusColor == null || !"sweat+".equals(key)) {
            return label();
        }
        final String plainName = name();
        final int plus = plainName.indexOf('+');
        if (plus < 0) {
            return label();
        }
        final TextColor baseColor = primaryColor();
        return Component.text("[", baseColor)
                .append(Component.text(plainName.substring(0, plus), baseColor))
                .append(Component.text("+", plusColor))
                .append(Component.text(plainName.substring(plus + 1) + "]", baseColor));
    }

    public Component prefix() {
        return label().append(Component.text(" "));
    }

    public Component prefix(final TextColor plusColor) {
        return label(plusColor).append(Component.text(" "));
    }

    /** The prefix shown in player-facing displays; Default players have no rank tag. */
    public Component displayPrefix() {
        return isDefault() ? Component.empty() : prefix();
    }

    public Component displayPrefix(final TextColor plusColor) {
        return isDefault() ? Component.empty() : prefix(plusColor);
    }

    public Component tabListName(final String playerName) {
        return tabListName(playerName, null);
    }

    public Component tabListName(final String playerName, final TextColor plusColor) {
        return displayPrefix(plusColor).append(Component.text(playerName, primaryColor()));
    }

    @Override
    public int compareTo(final Rank other) {
        final int byWeight = Integer.compare(other.weight, weight);
        if (byWeight != 0) {
            return byWeight;
        }
        final int byName = name().compareToIgnoreCase(other.name());
        return byName != 0 ? byName : name().compareTo(other.name());
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof final Rank rank)) {
            return false;
        }
        return weight == rank.weight
                && core == rank.core
                && name.equals(rank.name)
                && displayName.equals(rank.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, displayName, weight, core);
    }

    @Override
    public String toString() {
        return "Rank[name=" + name + ", displayName=" + displayName + ", weight=" + weight + ", core=" + core + "]";
    }

    /** Extracts the first TextColor found in a Component tree. */
    private static java.util.Optional<TextColor> extractFirstColor(final Component component) {
        if (component.color() != null) {
            return java.util.Optional.of(component.color());
        }
        for (final Component child : component.children()) {
            final java.util.Optional<TextColor> found = extractFirstColor(child);
            if (found.isPresent()) {
                return found;
            }
        }
        return java.util.Optional.empty();
    }
}