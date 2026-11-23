package io.github.near.sweats.players;

import io.github.near.sweats.storage.YamlFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Durable list of players who have actually joined the server.
 *
 * <p>{@code Bukkit.getOfflinePlayers()} reads the server user cache, which also contains profiles
 * created by NPC and shop plugins. Ranking that list is how fake names end up on a leaderboard.
 * This registry only accepts players seen in a real join event and the accounts in Sweats' own auth
 * database, so cached NPC profiles are never candidates. The name itself is never used to decide
 * whether somebody is real: a manager called {@code Shopkeeperkisuke} is ranked like anybody else
 * once they have joined. The file survives restarts, which keeps offline players on the boards once
 * they have joined at least once.
 */
public final class RealPlayerRegistry implements Listener {

    private final YamlFile file;
    private final Consumer<String> warn;
    private final Map<UUID, String> players = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public RealPlayerRegistry(final Path file, final Consumer<String> warn) {
        this.file = new YamlFile(file);
        this.warn = warn;
        load();
    }

    private void load() {
        players.clear();
        byName.clear();
        final var config = file.load(warn);
        if (config == null) {
            return;
        }
        final var section = config.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(false)) {
            final UUID id = parseUuid(key);
            final String name = section.getString(key);
            if (id != null && RealPlayerNames.valid(name)) {
                add(id, name);
            }
        }
        dirty = false;
    }

    /** Seeds the registry from Sweats' auth accounts, so existing players survive an upgrade. */
    public void seed(final Collection<Identity> identities) {
        if (identities == null) {
            return;
        }
        for (final Identity identity : identities) {
            if (identity != null && identity.id() != null) {
                add(identity.id(), identity.name());
            }
        }
    }

    /** Records a confirmed join. Returns true when this is a new entry. */
    public boolean markJoined(final UUID id, final String name) {
        if (id == null || !RealPlayerNames.valid(name)) {
            return false;
        }
        final boolean added = add(id, name);
        if (added) {
            save();
        }
        return added;
    }

    public boolean isReal(final UUID id) {
        return id != null && players.containsKey(id);
    }

    /** Case-insensitive lookup used to attribute name-only (protocol v1) votes. */
    public Optional<UUID> findByName(final String name) {
        if (!RealPlayerNames.valid(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /** The account name last seen for a UUID, or an empty string when unknown. */
    public String name(final UUID id) {
        final String name = players.get(id);
        return name == null ? "" : name;
    }

    public List<RealPlayer> all() {
        final List<RealPlayer> result = new ArrayList<>(players.size());
        for (final Map.Entry<UUID, String> entry : new LinkedHashMap<>(players).entrySet()) {
            result.add(new RealPlayer(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    public int size() {
        return players.size();
    }

    /** Writes the registry if anything changed. Safe to call repeatedly. */
    public void save() {
        if (!dirty) {
            return;
        }
        final var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.options().setHeader(List.of(
                "Players confirmed to have joined this server.",
                "Used by leaderboards to ignore cached NPC and shop-keeper profiles."));
        players.forEach((id, name) -> config.set("players." + id, name));
        if (file.save(config, warn)) {
            dirty = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        markJoined(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    private boolean add(final UUID id, final String name) {
        if (!RealPlayerNames.valid(name)) {
            return false;
        }
        final String clean = name.trim();
        final String previous = players.putIfAbsent(id, clean);
        if (previous != null) {
            // A name change should update the display name without changing identity.
            if (!previous.equals(clean)) {
                players.put(id, clean);
                byName.remove(previous.toLowerCase(Locale.ROOT), id);
                byName.put(clean.toLowerCase(Locale.ROOT), id);
                dirty = true;
            }
            return false;
        }
        byName.put(clean.toLowerCase(Locale.ROOT), id);
        dirty = true;
        return true;
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    public record RealPlayer(UUID id, String name) {}

    public record Identity(UUID id, String name) {}
}
