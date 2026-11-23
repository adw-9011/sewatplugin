package io.github.near.sweats.permission;

import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

final class PermissionStore {

    private final YamlFile file;
    private final Map<UUID, UserEntry> users = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<String>> ranks = new LinkedHashMap<>();

    PermissionStore(final Path file) {
        this.file = new YamlFile(file);
    }

    void load(final Consumer<String> warn) {
        users.clear();
        ranks.clear();

        final boolean existed = file.exists();
        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            if (!existed) {
                save(warn);
            }
            return;
        }

        final ConfigurationSection userSection = config.getConfigurationSection("users");
        if (userSection != null) {
            for (final String rawId : userSection.getKeys(false)) {
                final UUID id = parseUuid(rawId);
                if (id == null) {
                    warn.accept("Skipped invalid user id '" + rawId + "' in " + file.path());
                    continue;
                }
                final String name = userSection.getString(rawId + ".name", rawId);
                final LinkedHashSet<String> permissions = new LinkedHashSet<>();
                for (final String rawNode : userSection.getStringList(rawId + ".permissions")) {
                    final String node = normalizeNode(rawNode);
                    if (node == null) {
                        warn.accept("Skipped invalid permission '" + rawNode + "' for " + name);
                        continue;
                    }
                    permissions.add(node);
                }
                if (permissions.isEmpty()) {
                    continue;
                }
                users.put(id, new UserEntry(name, permissions));
            }
        }

        final ConfigurationSection rankSection = config.getConfigurationSection("ranks");
        if (rankSection != null) {
            for (final String rawRank : rankSection.getKeys(false)) {
                final LinkedHashSet<String> permissions = new LinkedHashSet<>();
                for (final String rawNode : rankSection.getStringList(rawRank)) {
                    final String node = normalizeNode(rawNode);
                    if (node == null) {
                        warn.accept("Skipped invalid permission '" + rawNode + "' for rank " + rawRank);
                        continue;
                    }
                    permissions.add(node);
                }
                if (!permissions.isEmpty()) {
                    ranks.put(Rank.key(rawRank), permissions);
                }
            }
        }
    }

    boolean save(final Consumer<String> warn) {
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Sweats permission grants. There is no rank inheritance: a rank grants only its",
                "own entries, and user grants are added on top."));

        final List<Map.Entry<UUID, UserEntry>> userEntries = new ArrayList<>(users.entrySet());
        userEntries.sort(Comparator.comparing(entry -> entry.getValue().name(), String.CASE_INSENSITIVE_ORDER));
        for (final Map.Entry<UUID, UserEntry> entry : userEntries) {
            final String path = "users." + entry.getKey();
            config.set(path + ".name", entry.getValue().name());
            config.set(path + ".permissions", entry.getValue().permissions().stream().sorted().toList());
        }

        final List<String> rankKeys = new ArrayList<>(ranks.keySet());
        rankKeys.sort(String.CASE_INSENSITIVE_ORDER);
        for (final String key : rankKeys) {
            config.set("ranks." + key, ranks.get(key).stream().sorted().toList());
        }

        return file.save(config, warn);
    }

    /** A copy of one user's stored entry, used to roll back a failed save. */
    UserState userState(final UUID id) {
        final UserEntry entry = users.get(id);
        return entry == null
                ? null
                : new UserState(entry.name(), Set.copyOf(entry.permissions()));
    }

    void restoreUser(final UUID id, final UserState state) {
        if (state == null) {
            users.remove(id);
        } else {
            users.put(id, new UserEntry(state.name(), new LinkedHashSet<>(state.permissions())));
        }
    }

    /** Copies of every rank grant, used to roll back a failed retention pass. */
    Map<String, Set<String>> rankStates() {
        final Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, LinkedHashSet<String>> entry : ranks.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return copy;
    }

    void restoreRanks(final Map<String, Set<String>> states) {
        ranks.clear();
        for (final Map.Entry<String, Set<String>> entry : states.entrySet()) {
            ranks.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
    }

    Set<String> rankState(final String rankKey) {
        final Set<String> permissions = ranks.get(rankKey);
        return permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    void restoreRank(final String rankKey, final Set<String> state) {
        if (state.isEmpty()) {
            ranks.remove(rankKey);
        } else {
            ranks.put(rankKey, new LinkedHashSet<>(state));
        }
    }

    List<String> userPermissions(final UUID id) {
        final UserEntry entry = users.get(id);
        return entry == null ? List.of() : entry.permissions().stream().sorted().toList();
    }

    List<String> rankPermissions(final String rankKey) {
        final Set<String> permissions = ranks.get(rankKey);
        return permissions == null ? List.of() : permissions.stream().sorted().toList();
    }

    Set<String> userPermissionSet(final UUID id) {
        final UserEntry entry = users.get(id);
        return entry == null ? Set.of() : entry.permissions();
    }

    Set<String> rankPermissionSet(final String rankKey) {
        final Set<String> permissions = ranks.get(rankKey);
        return permissions == null ? Set.of() : permissions;
    }

    String userName(final UUID id) {
        final UserEntry entry = users.get(id);
        return entry == null ? null : entry.name();
    }

    Optional<PermissionUser> knownUser(final String name) {
        for (final Map.Entry<UUID, UserEntry> entry : users.entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(name)) {
                return Optional.of(new PermissionUser(entry.getKey(), entry.getValue().name()));
            }
        }
        return Optional.empty();
    }

    boolean grantUser(final UUID id, final String name, final String node) {
        final String normalized = normalizeNode(node);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid permission node: " + node);
        }
        final UserEntry existing = users.get(id);
        if (existing == null) {
            final LinkedHashSet<String> permissions = new LinkedHashSet<>();
            permissions.add(normalized);
            users.put(id, new UserEntry(name, permissions));
            return true;
        }

        final boolean added = existing.permissions().add(normalized);
        final boolean renamed = !existing.name().equals(name);
        if (renamed) {
            users.put(id, new UserEntry(name, existing.permissions()));
        }
        return added || renamed;
    }

    boolean revokeUser(final UUID id, final String node) {
        final String normalized = normalizeNode(node);
        if (normalized == null) {
            return false;
        }
        final UserEntry existing = users.get(id);
        if (existing == null || !existing.permissions().remove(normalized)) {
            return false;
        }
        if (existing.permissions().isEmpty()) {
            users.remove(id);
        }
        return true;
    }

    boolean grantRank(final String rankKey, final String node) {
        final String normalized = normalizeNode(node);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid permission node: " + node);
        }
        return ranks.computeIfAbsent(rankKey, key -> new LinkedHashSet<>()).add(normalized);
    }

    boolean revokeRank(final String rankKey, final String node) {
        final String normalized = normalizeNode(node);
        if (normalized == null) {
            return false;
        }
        final Set<String> permissions = ranks.get(rankKey);
        if (permissions == null || !permissions.remove(normalized)) {
            return false;
        }
        if (permissions.isEmpty()) {
            ranks.remove(rankKey);
        }
        return true;
    }

    boolean retainRanks(final Set<String> validKeys) {
        return ranks.keySet().removeIf(key -> !validKeys.contains(key));
    }

    void touchUser(final UUID id, final String name) {
        final UserEntry entry = users.get(id);
        if (entry != null && !entry.name().equals(name)) {
            users.put(id, new UserEntry(name, entry.permissions()));
        }
    }

    private static String normalizeNode(final String raw) {
        if (raw == null) {
            return null;
        }
        final String node = raw.trim().toLowerCase(Locale.ROOT);
        return PermissionNodes.isValid(node) ? node : null;
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    private record UserEntry(String name, LinkedHashSet<String> permissions) {}

    record UserState(String name, Set<String> permissions) {}

}
