package io.github.near.sweats.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class OnlinePlayerNamesTest {

    @Test
    void addsNamesInCaseInsensitiveOrder() {
        final List<String> withBob = OnlinePlayerNames.updated(List.of(), "Bob", true);

        assertEquals(List.of("Bob"), withBob);
        assertEquals(List.of("amy", "Bob"), OnlinePlayerNames.updated(withBob, "amy", true));
    }

    @Test
    void removesNamesThatLeave() {
        assertEquals(List.of("Bob"), OnlinePlayerNames.updated(List.of("amy", "Bob"), "amy", false));
    }

    @Test
    void ignoresUnchangedSets() {
        final List<String> names = List.of("Bob");

        assertSame(names, OnlinePlayerNames.updated(names, "Bob", true));
        assertSame(names, OnlinePlayerNames.updated(names, "amy", false));
    }

    @Test
    void snapshotsCannotBeMutatedByAsyncReaders() {
        final List<String> names = OnlinePlayerNames.updated(List.of(), "Bob", true);

        assertThrows(UnsupportedOperationException.class, () -> names.add("amy"));
    }
}
