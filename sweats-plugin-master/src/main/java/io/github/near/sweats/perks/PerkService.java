package io.github.near.sweats.perks;

import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Answers "what can this player do right now?" from their current rank.
 *
 * <p>Only the plus-colour selection is stored. All other perks are derived, which is why rank
 * changes are immediate and never destroy homes, teams, ender chest contents or manual grants.
 */
public final class PerkService implements AutoCloseable {

    private static final String SWEAT_PLUS = "sweat+";

    private final RankService ranks;
    private final PlusColorStore store;
    private final Consumer<String> warn;
    private final List<Consumer<UUID>> changeListeners = new CopyOnWriteArrayList<>();

    public PerkService(final Path folder, final RankService ranks, final Consumer<String> warn) {
        this.ranks = Objects.requireNonNull(ranks, "ranks");
        this.warn = Objects.requireNonNull(warn, "warn");
        this.store = new PlusColorStore(folder.resolve("plus-colors.yml"));
        this.store.load(warn);
    }

    public RankPerks perks(final UUID id) {
        return RankPerks.of(ranks.rankOf(id));
    }

    /** The effective colour, or {@code null} when the player is not exactly Sweat+ or has none. */
    public TextColor plusColor(final UUID id) {
        if (!canCustomizePlusColor(id)) {
            return null;
        }
        return store.color(id).orElse(null);
    }

    public Optional<String> plusColorName(final UUID id) {
        if (!canCustomizePlusColor(id)) {
            return Optional.empty();
        }
        return store.name(id);
    }

    public boolean canCustomizePlusColor(final UUID id) {
        return SWEAT_PLUS.equals(ranks.rankOf(id).key());
    }

    public List<String> colorNames() {
        return List.of(
                "aqua", "black", "blue", "dark_aqua", "dark_blue", "dark_gray", "dark_green",
                "dark_purple", "dark_red", "gold", "gray", "green", "light_purple", "red",
                "white", "yellow");
    }

    /**
     * Stores a plus colour for a Sweat+ player. {@code reset} clears the selection.
     *
     * @return {@code true} when the selection changed
     */
    public boolean setPlusColor(final UUID id, final String raw) {
        if (!canCustomizePlusColor(id)) {
            throw new IllegalArgumentException("Only Sweat+ players can change the + colour.");
        }
        final String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("reset") || value.equals("off") || value.equals("default")) {
            final Optional<String> previous = store.name(id);
            if (store.remove(id)) {
                if (!store.save(warn)) {
                    previous.ifPresent(name -> store.put(id, name));
                    throw new IllegalStateException("Could not save the + colour.");
                }
                notifyChanged(id);
                return true;
            }
            return false;
        }

        final NamedTextColor color = PlusColorStore.color(value);
        if (color == null) {
            throw new IllegalArgumentException("Unknown colour: " + raw);
        }
        final String normalized = value.toLowerCase(Locale.ROOT);
        final Optional<String> previous = store.name(id);
        if (!store.put(id, normalized)) {
            return false;
        }
        if (!store.save(warn)) {
            if (previous.isPresent()) {
                store.put(id, previous.get());
            } else {
                store.remove(id);
            }
            throw new IllegalStateException("Could not save the + colour.");
        }
        notifyChanged(id);
        return true;
    }

    public void reload() {
        store.load(warn);
    }

    public void addChangeListener(final Consumer<UUID> listener) {
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void notifyChanged(final UUID id) {
        for (final Consumer<UUID> listener : changeListeners) {
            listener.accept(id);
        }
    }

    @Override
    public void close() {
        changeListeners.clear();
    }
}
