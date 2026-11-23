package io.github.near.sweats.nick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NickServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void setsNicknamesWithoutPrefixAndPersistsThem() {
        final UUID id = UUID.randomUUID();
        final NickService nicks = new NickService(folder, warnings::add);

        assertTrue(nicks.setNickname(id, "Alex", "Al"));
        assertEquals(Optional.of("Al"), nicks.nickname(id));
        assertEquals("Al", nicks.displayName(id, "Alex"));

        final NickService reloaded = new NickService(folder, warnings::add);

        assertEquals(Optional.of("Al"), reloaded.nickname(id));
        assertEquals("Al", reloaded.displayName(id, "Alex"));
        assertTrue(reloaded.knownPlayer("al").isPresent());
        assertEquals(id, reloaded.knownPlayer("al").orElseThrow().id());
        assertEquals(id, reloaded.knownPlayer("Alex").orElseThrow().id());
    }

    @Test
    void clearsNicknamesAndFallsBackToTheAccountName() {
        final NickService nicks = new NickService(folder, warnings::add);
        final UUID id = UUID.randomUUID();
        nicks.setNickname(id, "Alex", "Al");

        assertTrue(nicks.clearNickname(id));

        assertEquals(Optional.empty(), nicks.nickname(id));
        assertEquals("Alex", nicks.displayName(id, "Alex"));
        assertFalse(nicks.clearNickname(id));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void stripsAnAlreadyTypedPrefixAndCollapsesSpaces() {
        final NickService nicks = new NickService(folder, warnings::add);
        final UUID id = UUID.randomUUID();

        assertTrue(nicks.setNickname(id, "Alex", "  Cool   Alex  "));

        assertEquals(Optional.of("Cool Alex"), nicks.nickname(id));
    }

    @Test
    void rejectsInvalidAndDuplicateNicknames() {
        final NickService nicks = new NickService(folder, warnings::add);
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        nicks.setNickname(first, "Alex", "Al");

        assertThrows(IllegalArgumentException.class,
                () -> nicks.setNickname(second, "Sam", "Al"));
        assertThrows(IllegalArgumentException.class,
                () -> nicks.setNickname(second, "Sam", "bad~name"));
        assertThrows(IllegalArgumentException.class,
                () -> nicks.setNickname(second, "Sam", "   "));
        assertThrows(IllegalArgumentException.class,
                () -> nicks.setNickname(second, "Sam", "x".repeat(25)));
    }

    @Test
    void changingToTheSameNicknameReportsNoChange() {
        final NickService nicks = new NickService(folder, warnings::add);
        final UUID id = UUID.randomUUID();

        assertTrue(nicks.setNickname(id, "Alex", "Al"));
        assertFalse(nicks.setNickname(id, "Alex", "Al"));
    }

    @Test
    void aFailedSaveRollsBackSetAndClear() throws Exception {
        final NickService nicks = new NickService(folder, warnings::add);
        final UUID id = UUID.randomUUID();
        nicks.setNickname(id, "Alex", "Al");

        final Path path = folder.resolve("nicknames.yml");
        Files.delete(path);
        Files.createDirectories(path);
        Files.writeString(path.resolve("block"), "not a yaml file");

        assertThrows(IllegalStateException.class,
                () -> nicks.setNickname(id, "Alex", "New"));
        assertEquals(Optional.of("Al"), nicks.nickname(id));

        assertThrows(IllegalStateException.class, () -> nicks.clearNickname(id));
        assertEquals(Optional.of("Al"), nicks.nickname(id));
    }
}
