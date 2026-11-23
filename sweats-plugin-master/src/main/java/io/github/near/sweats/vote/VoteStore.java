package io.github.near.sweats.vote;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Per-player vote totals and voting streaks, persisted in {@code vote/votes.yml}.
 *
 * <p>A streak is a run of consecutive local days on which the player had at least one accepted
 * vote. Only the highest streak is shown on the board; the day list is kept so it survives reloads.
 */
public final class VoteStore {

    private static final int MAX_TRACKED_DAYS = 500;

    private final YamlFile file;
    private final Map<UUID, Long> totals = new LinkedHashMap<>();
    private final Map<UUID, Integer> bestStreaks = new LinkedHashMap<>();
    private final Map<UUID, Set<LocalDate>> days = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private boolean dirty;

    public VoteStore(final Path file) {
        this.file = new YamlFile(file);
    }

    public void load(final Consumer<String> warn) {
        totals.clear();
        bestStreaks.clear();
        days.clear();
        names.clear();
        dirty = false;

        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            return;
        }
        final ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (final String key : players.getKeys(false)) {
            final UUID id = parseUuid(key);
            if (id == null) {
                continue;
            }
            final long total = players.getLong(key + ".total", 0L);
            if (total > 0L) {
                totals.put(id, total);
            }
            bestStreaks.put(id, Math.max(0, players.getInt(key + ".best-streak", 0)));
            final Set<LocalDate> parsed = new HashSet<>();
            for (final String day : players.getStringList(key + ".days")) {
                try {
                    parsed.add(LocalDate.parse(day));
                } catch (final RuntimeException ignored) {
                    // Skip a malformed day rather than losing the rest of the history.
                }
            }
            if (!parsed.isEmpty()) {
                days.put(id, parsed);
            }
            final String name = players.getString(key + ".name");
            if (name != null && !name.isBlank()) {
                names.put(id, name);
            }
        }
    }

    /** Records one accepted vote and returns the player's new total. */
    public long record(final UUID id, final String name, final long timestampMillis) {
        final long total = totals.merge(id, 1L, Long::sum);
        if (name != null && !name.isBlank()) {
            names.put(id, name);
        }
        final LocalDate day = voteDay(timestampMillis);
        final Set<LocalDate> history = days.computeIfAbsent(id, ignored -> new HashSet<>());
        history.add(day);
        final LocalDate cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(MAX_TRACKED_DAYS);
        history.removeIf(value -> value.isBefore(cutoff));
        bestStreaks.put(id, Math.max(bestStreaks.getOrDefault(id, 0), longestStreak(history)));
        dirty = true;
        return total;
    }

    public long total(final UUID id) {
        return totals.getOrDefault(id, 0L);
    }

    public int bestStreak(final UUID id) {
        return bestStreaks.getOrDefault(id, 0);
    }

    public Map<UUID, VoteSummary> all() {
        final Map<UUID, VoteSummary> result = new LinkedHashMap<>();
        for (final UUID id : totals.keySet()) {
            result.put(id, new VoteSummary(id, names.getOrDefault(id, ""),
                    totals.get(id), bestStreak(id)));
        }
        return Map.copyOf(result);
    }

    public int trackedPlayers() {
        return totals.size();
    }

    public boolean dirty() {
        return dirty;
    }

    public void save(final Consumer<String> warn) {
        if (!dirty) {
            return;
        }
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Vote totals and streaks counted by Sweats' embedded Votifier receiver.",
                "Duplicate deliveries inside the dedupe window are not counted."));

        final List<UUID> ids = new ArrayList<>(totals.keySet());
        ids.sort(Comparator.comparing(
                id -> names.getOrDefault(id, id.toString()), String.CASE_INSENSITIVE_ORDER));
        for (final UUID id : ids) {
            final String path = "players." + id;
            config.set(path + ".name", names.getOrDefault(id, id.toString()));
            config.set(path + ".total", totals.get(id));
            config.set(path + ".best-streak", bestStreak(id));
            final List<String> sorted = days.getOrDefault(id, Set.of()).stream()
                    .sorted().map(LocalDate::toString).toList();
            config.set(path + ".days", sorted);
        }

        if (file.save(config, warn)) {
            dirty = false;
        }
    }

    /** Longest run of consecutive days in an unordered set of dates. */
    public static int longestStreak(final Set<LocalDate> days) {
        if (days == null || days.isEmpty()) {
            return 0;
        }
        final TreeSet<LocalDate> sorted = new TreeSet<>(days);
        int best = 0;
        int current = 0;
        LocalDate previous = null;
        for (final LocalDate day : sorted) {
            current = previous != null && day.equals(previous.plusDays(1)) ? current + 1 : 1;
            best = Math.max(best, current);
            previous = day;
        }
        return best;
    }

    private static LocalDate voteDay(final long timestampMillis) {
        final long now = System.currentTimeMillis();
        final long safe = timestampMillis > 0L
                && timestampMillis > now - 7L * 86_400_000L
                && timestampMillis < now + 86_400_000L ? timestampMillis : now;
        return Instant.ofEpochMilli(safe).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    public record VoteSummary(UUID id, String name, long total, int bestStreak) {}

    /** A mutable view used only for leaderboard lookups. */
    public Map<UUID, Long> totalsView() {
        return new HashMap<>(totals);
    }
}
