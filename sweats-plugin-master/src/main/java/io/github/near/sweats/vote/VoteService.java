package io.github.near.sweats.vote;

import io.github.near.sweats.players.RealPlayerRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

/**
 * Owns the embedded Votifier listener and turns incoming votes into server actions.
 *
 * <p>On first run it imports the token and RSA keys from an existing OyVotifier installation so
 * voting sites keep working after the standalone plugin is removed.
 */
public final class VoteService {

    private static final long DEDUPE_WINDOW_MS = 10 * 60 * 1000L;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Path dataFolder;
    private final RealPlayerRegistry realPlayers;
    private final VoteStore store;
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    private VoteSettings settings;
    private VoteServer server;
    private PrivateKey privateKey;
    private long lastVoteAt;

    public VoteService(
            final JavaPlugin plugin,
            final Path dataFolder,
            final RealPlayerRegistry realPlayers) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dataFolder = dataFolder;
        this.realPlayers = realPlayers;
        this.store = new VoteStore(dataFolder.resolve("vote/votes.yml"));
        this.store.load(logger::warning);
    }

    /** Notified after an accepted vote has been attributed to a real player. */
    public void addListener(final Runnable listener) {
        listeners.add(listener);
    }

    /** Reads config, migrates old Votifier credentials and binds the listener. */
    public void start() {
        stop();
        this.settings = VoteSettings.from(plugin.getConfig(), dataFolder);
        if (!settings.enabled()) {
            logger.info("Votifier listener is disabled in config.yml");
            return;
        }

        migrateFromOyVotifier();
        this.privateKey = loadPrivateKey(settings.privateKey());

        try {
            this.server = new VoteServer(
                    settings.host(),
                    settings.port(),
                    settings.token(),
                    privateKey,
                    settings.allowV1(),
                    this::onVote,
                    logger);
            server.start();
        } catch (final IOException exception) {
            logger.severe("Could not bind the Votifier listener on "
                    + settings.host() + ":" + settings.port() + ": " + exception.getMessage());
            this.server = null;
        }
    }

    /** Stops the listener, releasing the port and flushing vote totals. */
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
            logger.info("Votifier listener stopped");
        }
        store.save(logger::warning);
    }

    /** Rebinds with the current config, used by {@code /sweats reload}. */
    public void reload() {
        start();
    }

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    /** Vote totals and streaks, for the leaderboard. */
    public VoteStore votes() {
        return store;
    }

    /** Status lines for {@code /sweats vote status}. */
    public List<String> statusLines() {
        final List<String> lines = new ArrayList<>();
        lines.add("Votifier listener: " + (isRunning() ? "running" : "stopped"));
        if (settings == null) {
            return lines;
        }
        lines.add("Address: " + settings.host() + ":" + settings.port());
        lines.add("Protocol v1 (RSA): " + (privateKey != null && settings.allowV1()
                ? "ready" : "unavailable"));
        lines.add("Protocol v2 (token): " + (settings.token().isBlank()
                ? "no token configured" : "ready"));
        lines.add("Votes received: " + received.get() + " (duplicates ignored: "
                + duplicates.get() + ")");
        lines.add("Last vote: " + (lastVoteAt == 0 ? "never"
                : new java.util.Date(lastVoteAt).toInstant().toString()));
        lines.add("Players with votes: " + store.trackedPlayers());
        return lines;
    }

    /** Feeds a synthetic vote through the same path as a real network delivery. */
    public String simulate(final String player, final String site) {
        if (!isRunning()) {
            return "The Votifier listener is not running.";
        }
        final String safePlayer = clean(player, 16);
        if (safePlayer.isEmpty()) {
            return "Give a player name to simulate a vote.";
        }
        onVote(new Vote(site == null || site.isBlank() ? "Minecraft.Buzz" : clean(site, 64),
                safePlayer, "127.0.0.1", System.currentTimeMillis(), 0, ""));
        return "Simulated vote for " + safePlayer + ".";
    }

    // ------------------------------------------------------------------------------------------
    // Migration from the standalone plugin

    private void migrateFromOyVotifier() {
        final Path plugins = dataFolder.getParent();
        if (plugins == null) {
            return;
        }

        final Path key = settings.privateKey();
        if (!Files.isRegularFile(key)) {
            for (final String name : List.of("oyvotifier", "NuVotifier")) {
                final Path legacyKey = plugins.resolve(name + "/rsa/private.key");
                if (Files.isRegularFile(legacyKey)) {
                    try {
                        Files.createDirectories(key.getParent());
                        Files.copy(legacyKey, key, StandardCopyOption.REPLACE_EXISTING);
                        final Path legacyPublic = legacyKey.resolveSibling("public.key");
                        if (Files.isRegularFile(legacyPublic)) {
                            Files.copy(legacyPublic, key.resolveSibling("public.key"),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                        logger.info("Imported Votifier RSA keys from plugins/" + name);
                    } catch (final IOException exception) {
                        logger.warning("Could not import Votifier keys: " + exception.getMessage());
                    }
                    break;
                }
            }
        }

        if (settings.token().isBlank()) {
            for (final String name : List.of("oyvotifier", "NuVotifier")) {
                final Path legacyConfig = plugins.resolve(name + "/config.yml");
                if (!Files.isRegularFile(legacyConfig)) {
                    continue;
                }
                try {
                    final Object parsed = new Yaml().load(Files.readString(legacyConfig, StandardCharsets.UTF_8));
                    if (!(parsed instanceof final Map<?, ?> root)) {
                        continue;
                    }
                    if (!(root.get("tokens") instanceof final Map<?, ?> tokens)) {
                        continue;
                    }
                    final Object token = tokens.get("default");
                    if (token instanceof final String text && !text.isBlank()) {
                        plugin.getConfig().set("votes.token", text);
                        plugin.saveConfig();
                        settings = settings.withToken(text);
                        logger.info("Imported the Votifier token from plugins/" + name);
                    }
                } catch (final RuntimeException | IOException exception) {
                    logger.warning("Could not import the Votifier token: " + exception.getMessage());
                }
                break;
            }
        }
    }

    private PrivateKey loadPrivateKey(final Path path) {
        if (!Files.isRegularFile(path)) {
            logger.warning("No Votifier RSA key at " + path + " - protocol v1 votes will be rejected");
            return null;
        }
        try {
            final String base64 = Files.readString(path, StandardCharsets.UTF_8)
                    .replaceAll("-----[^-]+-----", "")
                    .replaceAll("\\s", "");
            final byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (final Exception exception) {
            logger.warning("Could not load the Votifier RSA key at " + path + ": "
                    + exception.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Vote handling

    private void onVote(final Vote vote) {
        final String key = vote.dedupeKey();
        final long now = System.currentTimeMillis();
        final Long previous = recent.put(key, now);
        if (previous != null && now - previous < DEDUPE_WINDOW_MS) {
            duplicates.incrementAndGet();
            logger.fine("Ignoring duplicate vote from " + vote.username());
            return;
        }
        if (recent.size() > 512) {
            final Iterator<Map.Entry<String, Long>> iterator = recent.entrySet().iterator();
            while (iterator.hasNext()) {
                if (now - iterator.next().getValue() > DEDUPE_WINDOW_MS) {
                    iterator.remove();
                }
            }
        }

        received.incrementAndGet();
        lastVoteAt = now;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> deliver(vote));
        } catch (final IllegalStateException exception) {
            logger.warning("Vote from " + vote.username() + " arrived while the server was stopping");
        }
    }

    private void deliver(final Vote vote) {
        logger.info("Vote received: " + vote.username() + " via " + vote.service());
        recordVote(vote);

        if (!settings.broadcast().isBlank()) {
            try {
                final Component message = MINI_MESSAGE.deserialize(
                        settings.broadcast(),
                        Placeholder.unparsed("player", clean(vote.username(), 32)),
                        Placeholder.unparsed("site", clean(vote.service(), 64)));
                plugin.getServer().broadcast(message);
            } catch (final RuntimeException exception) {
                logger.warning("Invalid MiniMessage in votes.broadcast: " + exception.getMessage());
            }
        }

        for (final String command : settings.rewards()) {
            final String resolved = command
                    .replace("{player}", clean(vote.username(), 32))
                    .replace("{site}", clean(vote.service(), 64));
            if (resolved.isBlank()) {
                continue;
            }
            try {
                plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(), resolved);
            } catch (final RuntimeException exception) {
                logger.warning("Could not run vote reward command: " + exception.getMessage());
            }
        }
    }

    /**
     * Attributes a vote to a real player and updates their total and streak. Name-only votes are
     * matched against known accounts rather than {@code Bukkit.getOfflinePlayer(name)}, which would
     * create another fake profile in the user cache.
     */
    private void recordVote(final Vote vote) {
        final String name = clean(vote.username(), 16);
        UUID id = parseUuid(vote.uuid());
        if (id == null || !realPlayers.isReal(id)) {
            final Player online = name.isEmpty() ? null : Bukkit.getPlayerExact(name);
            if (online != null && realPlayers.isReal(online.getUniqueId())) {
                id = online.getUniqueId();
            } else {
                id = realPlayers.findByName(name).orElse(null);
            }
        }
        if (id == null || !realPlayers.isReal(id)) {
            logger.fine("Not counting vote for " + name + ": not a known player");
            return;
        }

        store.record(id, name, vote.timestamp());
        store.save(logger::warning);
        for (final Runnable listener : listeners) {
            try {
                listener.run();
            } catch (final RuntimeException exception) {
                logger.warning("A vote listener failed: " + exception.getMessage());
            }
        }
    }

    private static UUID parseUuid(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    /** Keeps site-provided text from smuggling command arguments or MiniMessage tags. */
    private static String clean(final String value, final int maxLength) {
        if (value == null) {
            return "";
        }
        final String filtered = value.replaceAll("[^A-Za-z0-9_.-]", "");
        return filtered.length() > maxLength ? filtered.substring(0, maxLength) : filtered;
    }
}
