package io.github.near.sweats.mapart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapArtLibraryTest {

    @TempDir
    Path folder;

    private static void writePng(final Path file, final int width, final int height) throws Exception {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", file.toFile());
    }

    private MapArtLibrary library() {
        final MapArtLibrary library = new MapArtLibrary(folder);
        library.refresh(warning -> {});
        return library;
    }

    @Test
    void listsOnlyImageFiles() throws Exception {
        writePng(folder.resolve("one.png"), 16, 16);
        writePng(folder.resolve("TWO.JPG"), 16, 16);
        Files.writeString(folder.resolve("notes.txt"), "not an image");
        Files.createDirectories(folder.resolve("a-subfolder"));

        final MapArtLibrary library = library();

        assertEquals(List.of("one.png", "TWO.JPG"), library.names());
        assertTrue(library.resolve("one.png").isPresent());
        assertFalse(library.resolve("notes.txt").isPresent());
    }

    @Test
    void resolvesWithOrWithoutExtensionIgnoringCase() throws Exception {
        writePng(folder.resolve("image01.png"), 16, 16);
        final MapArtLibrary library = library();

        assertTrue(library.resolve("image01.png").isPresent());
        assertTrue(library.resolve("image01").isPresent());
        assertTrue(library.resolve("IMAGE01.PNG").isPresent());
        assertTrue(library.resolve("  image01  ").isPresent());
        assertFalse(library.resolve("image01.gif").isPresent());
    }

    @Test
    void refusesNamesThatCouldReachOutsideTheFolder() throws Exception {
        writePng(folder.resolve("image01.png"), 16, 16);
        final MapArtLibrary library = library();

        assertFalse(library.resolve("../image01.png").isPresent());
        assertFalse(library.resolve("sub/image01.png").isPresent());
        assertFalse(library.resolve("sub\\image01.png").isPresent());
        assertFalse(library.resolve("..").isPresent());
        assertFalse(library.resolve("").isPresent());
        assertFalse(library.resolve(".hidden").isPresent());
    }

    @Test
    void scalesEveryImageOntoTheMapGrid() throws Exception {
        writePng(folder.resolve("wide.png"), 300, 120);
        final MapArtLibrary library = library();

        final BufferedImage scaled = MapArtLibrary.load(library.resolve("wide.png").orElseThrow())
                .orElseThrow();

        assertEquals(MapArtLibrary.MAP_SIZE, scaled.getWidth());
        assertEquals(MapArtLibrary.MAP_SIZE, scaled.getHeight());
    }

    @Test
    void keepsAnImageThatIsAlreadyMapSized() throws Exception {
        writePng(folder.resolve("exact.png"), MapArtLibrary.MAP_SIZE, MapArtLibrary.MAP_SIZE);
        final MapArtLibrary library = library();

        final BufferedImage loaded = MapArtLibrary.load(library.resolve("exact.png").orElseThrow())
                .orElseThrow();

        assertEquals(MapArtLibrary.MAP_SIZE, loaded.getWidth());
        assertEquals(MapArtLibrary.MAP_SIZE, loaded.getHeight());
    }

    @Test
    void aBrokenImageIsRefusedInsteadOfThrowing() throws Exception {
        Files.writeString(folder.resolve("broken.png"), "this is not really a png");
        final MapArtLibrary library = library();

        assertTrue(library.resolve("broken.png").isPresent());
        assertTrue(MapArtLibrary.load(library.resolve("broken.png").orElseThrow()).isEmpty());
    }

    @Test
    void aMissingFolderJustMeansNoImages() {
        final MapArtLibrary library = new MapArtLibrary(folder.resolve("does-not-exist"));
        library.refresh(warning -> {});

        assertTrue(library.names().isEmpty());
        assertTrue(library.resolve("image01.png").isEmpty());
    }

    @Test
    void rejectsImagesBeyondTheDecodedPixelBudget() {
        assertTrue(MapArtLibrary.withinLimits(128, 128));
        assertTrue(MapArtLibrary.withinLimits(2_048, 2_048));
        assertFalse(MapArtLibrary.withinLimits(3_000, 3_000));
        assertFalse(MapArtLibrary.withinLimits(9_000, 1));
        assertFalse(MapArtLibrary.withinLimits(0, 128));
        assertFalse(MapArtLibrary.withinLimits(128, -1));
    }

    @Test
    void theItemNameDropsTheExtension() {
        assertEquals("image01", MapArtLibrary.displayName(Path.of("/tmp/gifs/image01.png")));
        assertEquals("johnny", MapArtLibrary.displayName(Path.of("johnny.gif")));
        assertEquals("noextension", MapArtLibrary.displayName(Path.of("noextension")));
    }
}
