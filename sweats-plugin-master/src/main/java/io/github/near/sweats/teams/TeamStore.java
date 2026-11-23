package io.github.near.sweats.teams;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Persistent teams, keyed by UUID and indexed by name and member. */
final class TeamStore {

    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_+-]{1,20}");

    static final class StoredTeam {

        private final UUID id;
        private String name;
        private UUID owner;
        private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        StoredTeam(final UUID id, final String name, final UUID owner) {
            this.id = id;
            this.name = name;
            this.owner = owner;
        }

        UUID id() {
            return id;
        }

        String name() {
            return name;
        }

        UUID owner() {
            return owner;
        }

        void owner(final UUID owner) {
            this.owner = owner;
        }

        LinkedHashSet<UUID> members() {
            return members;
        }
    }

    private final YamlFile file;
    private final Map<UUID, StoredTeam> teams = new LinkedHashMap<>();
    private final Map<String, UUID> byName = new LinkedHashMap<>();
    private final Map<UUID, UUID> byMember = new LinkedHashMap<>();

    TeamStore(final Path file) {
        this.file = new YamlFile(file);
    }

    void load(final Consumer<String> warn) {
        teams.clear();
        byName.clear();
        byMember.clear();

        final YamlConfiguration config = file.load(warn);
        if (config == null) {
            return;
        }
        final ConfigurationSection root = config.getConfigurationSection("teams");
        if (root == null) {
            return;
        }

        boolean repaired = false;
        for (final String idKey : root.getKeys(false)) {
            final UUID id;
            try {
                id = UUID.fromString(idKey);
            } catch (final IllegalArgumentException exception) {
                warn.accept("Ignored team with invalid UUID '" + idKey + "'.");
                repaired = true;
                continue;
            }
            final ConfigurationSection section = root.getConfigurationSection(idKey);
            if (section == null) {
                repaired = true;
                continue;
            }
            final String name = section.getString("name");
            if (name == null || !NAME_PATTERN.matcher(name).matches()) {
                warn.accept("Ignored team " + idKey + " with invalid name '" + name + "'.");
                repaired = true;
                continue;
            }
            final String key = name.toLowerCase(Locale.ROOT);
            if (byName.containsKey(key)) {
                warn.accept("Ignored duplicate team name '" + name + "'.");
                repaired = true;
                continue;
            }

            final UUID owner;
            try {
                owner = UUID.fromString(String.valueOf(section.getString("owner")));
            } catch (final IllegalArgumentException exception) {
                warn.accept("Ignored team '" + name + "' with an invalid owner.");
                repaired = true;
                continue;
            }
            if (byMember.containsKey(owner)) {
                warn.accept("Ignored team '" + name + "' because its owner is in another team.");
                repaired = true;
                continue;
            }

            final StoredTeam team = new StoredTeam(id, name, owner);
            team.members().add(owner);
            final List<String> storedMembers = section.getStringList("members");
            for (final String memberKey : storedMembers) {
                final UUID member;
                try {
                    member = UUID.fromString(memberKey);
                } catch (final IllegalArgumentException exception) {
                    warn.accept("Ignored invalid member '" + memberKey + "' in team '" + name + "'.");
                    repaired = true;
                    continue;
                }
                if (member.equals(owner)) {
                    continue;
                }
                if (byMember.containsKey(member)) {
                    warn.accept("Removed " + member + " from team '" + name
                            + "' because they are already in another team.");
                    repaired = true;
                    continue;
                }
                if (!team.members().add(member)) {
                    repaired = true;
                }
                byMember.put(member, id);
            }
            byMember.put(owner, id);
            teams.put(id, team);
            byName.put(key, id);
        }

        if (repaired) {
            save(warn);
        }
    }

    Collection<StoredTeam> teams() {
        return teams.values();
    }

    Optional<StoredTeam> team(final UUID id) {
        return Optional.ofNullable(teams.get(id));
    }

    Optional<StoredTeam> byName(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        final UUID id = byName.get(name.trim().toLowerCase(Locale.ROOT));
        return id == null ? Optional.empty() : team(id);
    }

    Optional<StoredTeam> byMember(final UUID member) {
        final UUID id = byMember.get(member);
        return id == null ? Optional.empty() : team(id);
    }

    boolean nameTaken(final String name) {
        return byName.containsKey(name.toLowerCase(Locale.ROOT));
    }

    void put(final StoredTeam team) {
        teams.put(team.id(), team);
        byName.put(team.name().toLowerCase(Locale.ROOT), team.id());
        for (final UUID member : team.members()) {
            byMember.put(member, team.id());
        }
    }

    void remove(final UUID id) {
        final StoredTeam removed = teams.remove(id);
        if (removed == null) {
            return;
        }
        byName.remove(removed.name().toLowerCase(Locale.ROOT));
        for (final UUID member : removed.members()) {
            byMember.remove(member);
        }
    }

    void addMember(final StoredTeam team, final UUID member) {
        team.members().add(member);
        byMember.put(member, team.id());
    }

    void removeMember(final StoredTeam team, final UUID member) {
        team.members().remove(member);
        byMember.remove(member);
    }

    boolean save(final Consumer<String> warn) {
        final YamlConfiguration config = new YamlConfiguration();
        for (final StoredTeam team : teams.values()) {
            final String path = "teams." + team.id();
            config.set(path + ".name", team.name());
            config.set(path + ".owner", team.owner().toString());
            final List<String> members = new ArrayList<>(team.members().size());
            for (final UUID member : team.members()) {
                members.add(member.toString());
            }
            config.set(path + ".members", members);
        }
        return file.save(config, warn);
    }
}
