package io.github.near.sweats.leaderboard;

import java.util.List;
import org.bukkit.Location;

/**
 * The placement operations behind {@code /leaderboard}.
 *
 * <p>Keeping them on a small interface means the command can be tested without starting a server
 * and without the scheduler, listeners and displays that {@link LeaderboardService} owns.
 */
public interface LeaderboardBoards {

    /** Whether boards are currently rendered; false when {@code leaderboard.enabled} is off. */
    boolean enabled();

    /** Every saved display position, including ones in worlds that are not loaded right now. */
    List<LeaderboardStore.StoredDisplay> displays();

    /** Saves a new playtime board at {@code location} and starts rendering it. */
    LeaderboardStore.StoredDisplay place(Location location);

    /** Saves a new board of the given category at {@code location}. */
    default LeaderboardStore.StoredDisplay place(
            final Location location, final LeaderboardCategory category) {
        return place(location);
    }

    /** Removes the saved board closest to {@code location} within {@code radius} blocks. */
    boolean removeNearest(Location location, double radius);
}
