package io.github.near.sweats.maintenance;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The switch and bypass list, stored in {@code maintenance.yml}.
 *
 * <p>{@link #state()} is a single volatile read, so the asynchronous pre-login event can consult
 * it without touching the file or taking a lock. Every change swaps in a new immutable state.
 */
public final class MaintenanceStore {

    /** Outcome of a change, so callers can distinguish "no change" from "not saved". */
    public enum Result {
        CHANGED,
        UNCHANGED,
        SAVE_FAILED
    }

    private static final List<String> HEADER = List.of(
            "Sweats maintenance mode.",
            "Managed by /maintenance; stop the server before editing this file by hand.");

    private final YamlFile file;
    private final Consumer<String> warn;

    private volatile State state = new State(false, List.of());

    public MaintenanceStore(final Path file, final Consumer<String> warn) {
        this.file = new YamlFile(file);
        this.warn = warn;
        load();
    }

    public State state() {
        return state;
    }

    public synchronized Result setEnabled(final boolean enabled) {
        if (state.enabled() == enabled) {
            return Result.UNCHANGED;
        }
        final State previous = state;
        state = new State(enabled, previous.bypass());
        if (!save()) {
            state = previous;
            return Result.SAVE_FAILED;
        }
        return Result.CHANGED;
    }

    public synchronized Result addBypass(final String name) {
        if (name == null || name.isBlank() || state.hasBypass(name)) {
            return Result.UNCHANGED;
        }
        final State previous = state;
        final List<String> bypass = new ArrayList<>(previous.bypass());
        bypass.add(name.trim());
        bypass.sort(String.CASE_INSENSITIVE_ORDER);
        state = new State(previous.enabled(), List.copyOf(bypass));
        if (!save()) {
            state = previous;
            return Result.SAVE_FAILED;
        }
        return Result.CHANGED;
    }

    public synchronized Result removeBypass(final String name) {
        if (name == null || name.isBlank()) {
            return Result.UNCHANGED;
        }
        final State previous = state;
        final List<String> bypass = new ArrayList<>(previous.bypass());
        final boolean removed =
                bypass.removeIf(entry -> entry.equalsIgnoreCase(name.trim()));
        if (!removed) {
            return Result.UNCHANGED;
        }
        state = new State(previous.enabled(), List.copyOf(bypass));
        if (!save()) {
            state = previous;
            return Result.SAVE_FAILED;
        }
        return Result.CHANGED;
    }

    private void load() {
        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            // Missing or unreadable. Writing now gives the owner a file to look at and keeps the
            // first /maintenance on from depending on a second save path.
            if (!file.exists()) {
                save();
            }
            return;
        }

        final List<String> bypass = new ArrayList<>();
        for (final String raw : config.getStringList("bypass")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            final String name = raw.trim();
            if (bypass.stream().noneMatch(entry -> entry.equalsIgnoreCase(name))) {
                bypass.add(name);
            }
        }
        bypass.sort(String.CASE_INSENSITIVE_ORDER);
        state = new State(config.getBoolean("enabled", false), List.copyOf(bypass));
    }

    private boolean save() {
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(HEADER);
        config.set("enabled", state.enabled());
        config.set("bypass", state.bypass());
        return file.save(config, warn);
    }

    /** Immutable snapshot, read from the async pre-login event. */
    public record State(boolean enabled, List<String> bypass) {

        public boolean hasBypass(final String name) {
            if (name == null) {
                return false;
            }
            final String lower = name.toLowerCase(Locale.ROOT);
            return bypass.stream().anyMatch(entry -> entry.toLowerCase(Locale.ROOT).equals(lower));
        }
    }
}
