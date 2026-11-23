package io.github.near.sweats.nick;

import io.github.near.sweats.nick.NickStore.StoredNick;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Owns player nicknames.
 *
 * <p>A nickname is always displayed with a {@value #PREFIX} prefix so it can never be mistaken
 * for the real Minecraft account name. Identity remains the {@link UUID}; the current account
 * name is only stored so offline lookups keep working after a name change.
 */
public final class NickService implements Listener {

    public static final String PREFIX = "";

    private static final Pattern VALID_NICKNAME =
            Pattern.compile("[A-Za-z0-9 _+.'-]{1,24}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final NickStore store;
    private final Consumer<String> warn;
    private final List<Consumer<UUID>> changeListeners = new ArrayList<>();

    public NickService(final Path folder, final Consumer<String> warn) {
        this.store = new NickStore(folder.resolve("nicknames.yml"));
        this.warn = Objects.requireNonNull(warn, "warn");
        this.store.load(warn);
    }

    /** The nickname without the display prefix, or empty when the player has none. */
    public Optional<String> nickname(final UUID id) {
        return store.find(id).map(StoredNick::nickname);
    }

    /** The last account name stored with a nickname entry, if one exists. */
    public Optional<String> accountName(final UUID id) {
        return store.find(id).map(StoredNick::name).filter(name -> !name.isBlank());
    }

    /** The prefixed nickname, or the real account name when no nickname is set. */
    public String displayName(final UUID id, final String playerName) {
        return store.find(id)
                .map(entry -> PREFIX + entry.nickname())
                .orElse(playerName);
    }

    /**
     * Sets a nickname. The prefix is stripped if the caller already typed it.
     *
     * @return {@code true} when the stored nickname changed
     * @throws IllegalArgumentException when the nickname is invalid or already in use
     * @throws IllegalStateException when the change could not be saved and was rolled back
     */
    public boolean setNickname(final UUID id, final String playerName, final String rawNickname) {
        final String nickname = validate(rawNickname);
        if (store.nicknameTakenByOther(id, nickname)) {
            throw new IllegalArgumentException(nickname + " is already in use as a nickname.");
        }
        final Optional<StoredNick> current = store.find(id);
        if (current.isPresent() && current.get().nickname().equals(nickname)) {
            return false;
        }
        store.put(id, playerName, nickname);
        if (!store.save(warn)) {
            restore(id, current);
            throw new IllegalStateException(
                    "Could not save the nickname; the change was rolled back.");
        }
        notifyChanged(id);
        return true;
    }

    /**
     * Removes a nickname.
     *
     * @throws IllegalStateException when the removal could not be saved and was rolled back
     */
    public boolean clearNickname(final UUID id) {
        final Optional<StoredNick> current = store.find(id);
        if (!store.remove(id)) {
            return false;
        }
        if (!store.save(warn)) {
            restore(id, current);
            throw new IllegalStateException(
                    "Could not save the nickname; the change was rolled back.");
        }
        notifyChanged(id);
        return true;
    }

    /** Resolves a real name or a nickname, with or without the {@value #PREFIX} prefix. */
    public Optional<KnownNick> knownPlayer(final String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        final String name = input.strip();
        final String bare = name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name;
        final var byNickname = store.findByNickname(bare);
        return byNickname.isPresent()
                ? byNickname.map(entry -> new KnownNick(entry.id(), entry.name()))
                : store.findByName(bare).map(entry -> new KnownNick(entry.id(), entry.name()));
    }

    public void addChangeListener(final Consumer<UUID> listener) {
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void reload() {
        store.load(warn);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        final Optional<StoredNick> previous = store.find(id);
        if (store.rename(id, event.getPlayer().getName()) && !store.save(warn)) {
            restore(id, previous);
        }
    }

    private void restore(final UUID id, final Optional<StoredNick> previous) {
        if (previous.isPresent()) {
            store.put(id, previous.get().name(), previous.get().nickname());
        } else {
            store.remove(id);
        }
    }

    private void notifyChanged(final UUID id) {
        for (final Consumer<UUID> listener : changeListeners) {
            listener.accept(id);
        }
    }

    private static String validate(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A nickname is required.");
        }
        String nickname = WHITESPACE.matcher(raw.strip()).replaceAll(" ");
        if (nickname.startsWith(PREFIX)) {
            nickname = nickname.substring(PREFIX.length()).strip();
        }
        if (nickname.isEmpty()) {
            throw new IllegalArgumentException("A nickname is required.");
        }
        if (!VALID_NICKNAME.matcher(nickname).matches()) {
            throw new IllegalArgumentException(
                    "Nicknames may only contain letters, numbers, spaces, '_', '-', '+', '.' "
                            + "and may be at most 24 characters long.");
        }
        return nickname;
    }

    public record KnownNick(UUID id, String name) {}
}
