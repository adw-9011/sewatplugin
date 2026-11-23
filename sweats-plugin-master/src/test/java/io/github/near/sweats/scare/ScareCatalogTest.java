package io.github.near.sweats.scare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScareCatalogTest {

    @Test
    void exposesTheDocumentedJumpscares() {
        assertEquals(List.of("jumpscare1", "jumpscare2", "jumpscare3", "jumpscare4"), ScareCatalog.ids());
    }

    @Test
    void looksUpIdsCaseInsensitively() {
        final Optional<ScarePreset> preset = ScareCatalog.byId("JumpScare2");

        assertTrue(preset.isPresent());
        assertEquals("jumpscare2", preset.get().id());
    }

    @Test
    void rejectsUnknownJumpscares() {
        assertTrue(ScareCatalog.byId("nope").isEmpty());
        assertTrue(ScareCatalog.byId("").isEmpty());
    }

    @Test
    void everyPresetCanActuallyBeShown() {
        for (final ScarePreset preset : ScareCatalog.all()) {
            assertFalse(preset.id().isBlank(), preset.id() + " needs an id");
            assertFalse(preset.sounds().isEmpty(), preset.id() + " needs at least one sound");
            assertFalse(preset.postEffects().isEmpty(), preset.id() + " needs a post effect");
            assertTrue(preset.durationTicks() > 0, preset.id() + " needs a duration");
            assertFalse(preset.title().equals(net.kyori.adventure.text.Component.empty()));
        }
    }

    @Test
    void defaultPresetIsAvailable() {
        assertEquals(ScareCatalog.DEFAULT_ID, ScareCatalog.defaultPreset().id());
    }
}
