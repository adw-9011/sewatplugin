package io.github.near.sweats.mapart;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * The images that {@code /gif} can hand out.
 *
 * <p>Images are plain files in one folder ({@code plugins/Sweats/gifs/} by default). Only names
 * that were actually listed from that folder can be resolved, so a request can never reach a file
 * outside it - which makes path traversal impossible without any extra path juggling.
 *
 * <p>Reading an image is deliberately kept to plain Java (no Bukkit types), so the folder, name and
 * scaling rules can be unit tested without a server.
 */
public final class MapArtLibrary {

    /** A Minecraft map is always 128x128 pixels. */
    public static final int MAP_SIZE = 128;

    /** Extensions an {@link ImageIO} reader is expected to understand, in lookup order. */
    private static final List<String> EXTENSIONS = List.of("png", "jpg", "jpeg", "gif", "bmp");

    /** Letters, digits, dot, dash and underscore; no separators, so nothing can escape the folder. */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** Anything bigger is refused instead of risking a heap spike on a huge image. */
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;

    /** Header limits checked before decoding, so a compressed image cannot expand into a huge heap. */
    private static final int MAX_DIMENSION = 8_192;

    private static final long MAX_PIXELS = 4_194_304L;

    private final Path folder;

    /** Lowercase file name to file. Rebuilt by {@link #refresh(Consumer)}. */
    private volatile Map<String, Path> files = Map.of();

    /** Display names in tab-completion order. Rebuilt by {@link #refresh(Consumer)}. */
    private volatile List<String> names = List.of();

    public MapArtLibrary(final Path folder) {
        this.folder = folder;
    }

    /** Re-reads the folder; called on startup and on {@code /sweats reload}. */
    public void refresh(final Consumer<String> warn) {
        if (!Files.isDirectory(folder)) {
            this.files = Map.of();
            this.names = List.of();
            return;
        }

        final Map<String, Path> found = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(folder)) {
            entries.filter(Files::isRegularFile)
                    .filter(path -> isImage(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> found.put(
                            path.getFileName().toString().toLowerCase(Locale.ROOT), path.toAbsolutePath()));
        } catch (final IOException | UncheckedIOException exception) {
            warn.accept("Could not read " + folder + ": " + exception.getMessage());
        }

        this.files = Map.copyOf(found);
        this.names = found.values().stream()
                .map(path -> path.getFileName().toString())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** The file names as they sit on disk, ready for tab completion and the "available" list. */
    public List<String> names() {
        return names;
    }

    /**
     * Finds a file by name, with or without its extension, ignoring case.
     *
     * <p>{@code image01.png}, {@code image01} and {@code IMAGE01.PNG} all resolve to the same file.
     */
    public Optional<Path> resolve(final String requested) {
        final String name = requested == null ? "" : requested.trim();
        if (name.isEmpty() || !VALID_NAME.matcher(name).matches()) {
            return Optional.empty();
        }

        final String lower = name.toLowerCase(Locale.ROOT);
        final Path exact = files.get(lower);
        if (exact != null) {
            return Optional.of(exact);
        }

        for (final String extension : EXTENSIONS) {
            final Path withExtension = files.get(lower + "." + extension);
            if (withExtension != null) {
                return Optional.of(withExtension);
            }
        }
        return Optional.empty();
    }

    /** Reads an image and fits it onto the 128x128 map grid. */
    public static Optional<BufferedImage> load(final Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return Optional.empty();
            }
            try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
                if (input == null) {
                    return Optional.empty();
                }
                final Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    return Optional.empty();
                }

                final ImageReader reader = readers.next();
                try {
                    reader.setInput(input, false, true);
                    if (!withinLimits(reader.getWidth(0), reader.getHeight(0))) {
                        return Optional.empty();
                    }
                    final BufferedImage image = reader.read(0);
                    return image == null ? Optional.empty() : Optional.of(scale(image));
                } finally {
                    reader.dispose();
                }
            }
        } catch (final IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    static boolean withinLimits(final int width, final int height) {
        return width > 0
                && height > 0
                && width <= MAX_DIMENSION
                && height <= MAX_DIMENSION
                && (long) width * height <= MAX_PIXELS;
    }

    /** Fits any image into a 128x128 square, keeping large sources readable. */
    static BufferedImage scale(final BufferedImage source) {
        if (source.getWidth() == MAP_SIZE && source.getHeight() == MAP_SIZE) {
            return source;
        }

        final BufferedImage scaled = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, MAP_SIZE, MAP_SIZE, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /** True when the file name ends in an extension ImageIO is expected to read. */
    public static boolean isImage(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return false;
        }
        return EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** The name shown on the map: the file name without its extension. */
    public static String displayName(final Path file) {
        final String fileName = file.getFileName().toString();
        final int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
