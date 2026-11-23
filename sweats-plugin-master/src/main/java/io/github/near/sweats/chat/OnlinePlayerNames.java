package io.github.near.sweats.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * An immutable view of the online player names.
 *
 * <p>Bukkit's online player collection may only be read from the main thread, but chat decoration
 * happens asynchronously. This listener keeps a snapshot up to date on join and quit, so async
 * readers only ever touch an immutable list.
 */
public final class OnlinePlayerNames implements Listener {

    private volatile List<String> names = List.of();

    /** Must run on the main thread, once, during plugin enable. */
    public void initialize() {
        final List<String> current = new ArrayList<>(Bukkit.getOnlinePlayers().size());
        Bukkit.getOnlinePlayers().forEach(player -> current.add(player.getName()));
        current.sort(String.CASE_INSENSITIVE_ORDER);
        this.names = List.copyOf(current);
    }

    public List<String> names() {
        return names;
    }

    /** Rebuilds the snapshot while leaving out players for whom {@code hidden} is true. */
    public void refresh(final Predicate<UUID> hidden) {
        final List<String> current = new ArrayList<>(Bukkit.getOnlinePlayers().size());
        Bukkit.getOnlinePlayers().forEach(player -> {
            if (!hidden.test(player.getUniqueId())) {
                current.add(player.getName());
            }
        });
        current.sort(String.CASE_INSENSITIVE_ORDER);
        this.names = List.copyOf(current);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.names = updated(this.names, event.getPlayer().getName(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.names = updated(this.names, event.getPlayer().getName(), false);
    }

    static List<String> updated(final List<String> current, final String name, final boolean added) {
        if (added ? current.contains(name) : !current.contains(name)) {
            return current;
        }

        final List<String> next = new ArrayList<>(current.size() + 1);
        if (added) {
            next.addAll(current);
            next.add(name);
        } else {
            for (final String existing : current) {
                if (!existing.equals(name)) {
                    next.add(existing);
                }
            }
        }
        next.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(next);
    }
}
