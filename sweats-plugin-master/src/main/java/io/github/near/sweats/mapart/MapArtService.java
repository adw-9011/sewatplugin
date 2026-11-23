package io.github.near.sweats.mapart;

import io.github.near.sweats.SweatsPlugin;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Turns an image file into a mapart item and hands it to a player, the way {@code /give} would.
 *
 * <p>The item goes into the first free inventory slots; nothing already in the inventory is
 * replaced. When the inventory is full the leftover maps drop at the player's feet, which is what
 * vanilla {@code /give} does too.
 *
 * <p>Each image reuses one {@link MapView} per world, so asking for the same picture again gives
 * the same map art instead of burning a new map id every time.
 */
public final class MapArtService {

    private final SweatsPlugin plugin;
    private final Map<String, MapView> views = new ConcurrentHashMap<>();
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("Sweats-Images").daemon(true).factory());

    private MapArtSettings settings;
    private MapArtLibrary library;

    public MapArtService(final SweatsPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    /** Re-reads the config and rescans the image folder. */
    public void reloadSettings() {
        this.settings = MapArtSettings.from(plugin.getConfig());

        final Path folder = plugin.getDataFolder().toPath().resolve(settings.folder());
        try {
            Files.createDirectories(folder);
        } catch (final IOException exception) {
            plugin.getLogger().warning("Could not create " + folder + ": " + exception.getMessage());
        }

        this.library = new MapArtLibrary(folder);
        this.library.refresh(plugin.getLogger()::warning);
        // Forget cached views so a replaced image gets a fresh map id; handed-out maps keep their pixels.
        clearViews();

        if (!library.names().isEmpty()) {
            plugin.getLogger().info("Loaded " + library.names().size() + " /gif image(s): "
                    + String.join(", ", library.names()));
        }
    }

    /** Drops cached map views and stops accepting image work on disable. */
    public void clearState() {
        clearViews();
        imageExecutor.shutdownNow();
    }

    /** Names available to {@code /gif}, as they sit on disk. */
    public List<String> names() {
        return library.names();
    }

    /** Human readable folder, for the "drop images here" hint. */
    public String folderDisplay() {
        return "plugins/" + plugin.getDataFolder().getName() + "/" + settings.folder();
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** What happened to a {@code /gif} request. */
    public enum Status {
        /** The map was added to the inventory (or dropped if the inventory was full). */
        GIVEN,
        /** No image with that name exists in the folder. */
        UNKNOWN,
        /** The file exists but could not be decoded as an image, or exceeded the size limits. */
        UNREADABLE,
        /** {@code gifs.enabled} is false. */
        DISABLED,
        /** The requesting player left before the map was ready. */
        OFFLINE
    }

    /**
     * @param status what happened
     * @param name the image name shown to the player, or the request when nothing was found
     */
    public record Result(Status status, String name) {}

    /**
     * Builds the mapart for {@code requested} and adds it to the player's inventory.
     *
     * <p>Image decoding runs off the main thread; the future completes on the main thread so
     * callers can safely touch inventories and chat.
     */
    public CompletableFuture<Result> give(final Player player, final String requested) {
        if (!settings.enabled()) {
            return CompletableFuture.completedFuture(new Result(Status.DISABLED, requested));
        }

        final Optional<Path> resolved = library.resolve(requested);
        if (resolved.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(Status.UNKNOWN, requested));
        }

        final Path file = resolved.get();
        final String name = MapArtLibrary.displayName(file);
        final MapView cached = views.get(viewKey(player.getWorld(), file));
        if (cached != null) {
            return CompletableFuture.completedFuture(deliver(player, cached, name));
        }

        return CompletableFuture
                .supplyAsync(() -> MapArtLibrary.load(file), imageExecutor)
                .thenCompose(image -> image.isEmpty()
                        ? CompletableFuture.completedFuture(new Result(Status.UNREADABLE, name))
                        : createAndDeliver(player, file, name, image.get()));
    }

    private CompletableFuture<Result> createAndDeliver(
            final Player player, final Path file, final String name, final BufferedImage image) {
        if (!plugin.isEnabled()) {
            return CompletableFuture.completedFuture(new Result(Status.OFFLINE, name));
        }

        final CompletableFuture<Result> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                result.complete(new Result(Status.OFFLINE, name));
                return;
            }

            final World world = player.getWorld();
            final MapView view = views.computeIfAbsent(
                    viewKey(world, file), key -> createView(world, image));
            result.complete(deliver(player, view, name));
        });
        return result;
    }

    private static Result deliver(final Player player, final MapView view, final String name) {
        final ItemStack map = mapItem(view, name);
        final Map<Integer, ItemStack> leftover = player.getInventory().addItem(map);
        leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        return new Result(Status.GIVEN, name);
    }

    private static ItemStack mapItem(final MapView view, final String name) {
        final ItemStack map = new ItemStack(Material.FILLED_MAP);
        final MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        meta.displayName(Component.text(name, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        map.setItemMeta(meta);
        return map;
    }

    private static MapView createView(final World world, final BufferedImage image) {
        final MapView view = Bukkit.createMap(world);
        for (final MapRenderer renderer : List.copyOf(view.getRenderers())) {
            view.removeRenderer(renderer);
        }
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        view.addRenderer(new ImageRenderer(image));
        return view;
    }

    /** Draws the picture once; the map canvas keeps the pixels from then on. */
    private static final class ImageRenderer extends MapRenderer {

        private volatile BufferedImage image;

        ImageRenderer(final BufferedImage image) {
            super(false);
            this.image = image;
        }

        @Override
        public void render(final MapView view, final MapCanvas canvas, final Player player) {
            final BufferedImage pending = image;
            if (pending == null) {
                return;
            }
            canvas.drawImage(0, 0, pending);
            image = null;
        }
    }

    private void clearViews() {
        views.clear();
    }

    private static String viewKey(final World world, final Path file) {
        return world.getUID() + "|" + file.getFileName();
    }
}
