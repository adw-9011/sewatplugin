package io.github.near.sweats.leaderboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

/**
 * One placed leaderboard board.
 *
 * <p>A normal text display is shared by everyone, so it cannot show a different first line for
 * each viewer. Instead every viewer gets a private text display at the same spot, hidden from
 * everybody else, and only that player is shown the entity. The shared top ten is rendered into
 * the same component, so there is still only one entity per viewer.
 */
final class LeaderboardDisplay {

    private final LeaderboardService service;
    private final LeaderboardStore.StoredDisplay stored;
    private final Location location;
    private final Map<UUID, TextDisplay> entities = new HashMap<>();
    private final Map<UUID, Component> rendered = new HashMap<>();
    private final LeaderboardDecor decor;

    LeaderboardDisplay(
            final LeaderboardService service,
            final LeaderboardStore.StoredDisplay stored,
            final Location location) {
        this.service = service;
        this.stored = stored;
        this.location = location;
        this.decor = location.getWorld() == null ? null : LeaderboardDecor.spawn(
                location.getWorld(), location, stored.category(), service.plugin().getLogger());
    }

    void update(final Collection<? extends Player> online, final List<LeaderboardEntry> ranking) {
        if (!service.settings().enabled() || location.getWorld() == null) {
            removeAll();
            return;
        }

        final Set<UUID> keep = new HashSet<>();
        for (final Player player : online) {
            if (!service.canSee(player)) {
                continue;
            }
            if (!player.getWorld().equals(location.getWorld())) {
                continue;
            }
            if (player.getLocation().distanceSquared(location)
                    > service.settings().viewDistanceSquared()) {
                continue;
            }
            if (!location.getWorld().isChunkLoaded(
                    location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }

            keep.add(player.getUniqueId());
            render(player, ranking);
            if (decor != null) {
                decor.show(service.plugin(), player);
            }
        }

        for (final UUID id : new ArrayList<>(entities.keySet())) {
            if (!keep.contains(id)) {
                remove(id);
            }
        }
    }

    LeaderboardCategory category() {
        return stored.category();
    }

    /** Removes the private entity for one player, for example immediately after they quit. */
    void hide(final Player player) {
        remove(player.getUniqueId());
    }

    void removeAll() {
        for (final UUID id : new ArrayList<>(entities.keySet())) {
            remove(id);
        }
        if (decor != null) {
            decor.removeAll(service.plugin());
        }
    }

    private void render(final Player player, final List<LeaderboardEntry> ranking) {
        final UUID id = player.getUniqueId();
        final Component text = LeaderboardLayout.display(stored.category(),
                service.score(stored.category(), player), ranking);

        TextDisplay display = entities.get(id);
        if (display == null || !display.isValid()) {
            if (display != null) {
                remove(id);
            }
            display = spawn();
            if (display == null) {
                return;
            }
            entities.put(id, display);
            rendered.put(id, text);
            display.text(text);
            player.showEntity(service.plugin(), display);
            return;
        }

        if (!text.equals(rendered.get(id))) {
            display.text(text);
            rendered.put(id, text);
        }
    }

    private TextDisplay spawn() {
        try {
            return location.getWorld().spawn(location, TextDisplay.class, entity -> {
                entity.setVisibleByDefault(false);
                entity.setPersistent(false);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setSilent(true);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                entity.setDefaultBackground(false);
                entity.setShadowed(true);
                entity.setSeeThrough(false);
                entity.setLineWidth(200);
            });
        } catch (final RuntimeException exception) {
            service.plugin().getLogger().fine(
                    "Could not create a leaderboard display at " + position() + ": "
                            + exception.getMessage());
            return null;
        }
    }

    private void remove(final UUID id) {
        final TextDisplay display = entities.remove(id);
        rendered.remove(id);
        if (display == null) {
            return;
        }

        final Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline()) {
            player.hideEntity(service.plugin(), display);
            if (decor != null) {
                decor.hide(service.plugin(), player);
            }
        }
        if (!display.isDead()) {
            display.remove();
        }
    }

    private String position() {
        return stored.world() + " " + stored.x() + " " + stored.y() + " " + stored.z();
    }

    @Override
    public String toString() {
        return "LeaderboardDisplay[" + stored.category().key() + " " + position() + "]";
    }
}
