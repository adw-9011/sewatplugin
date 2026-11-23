package io.github.near.sweats.show;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;

/** Covers the {@code 64x Diamond} label that {@code /show} prints. */
class ShowServiceTest {

    private static String plain(final Component component) {
        final StringBuilder out = new StringBuilder();
        collect(component, out);
        return out.toString();
    }

    private static void collect(final Component component, final StringBuilder out) {
        if (component instanceof final TextComponent text) {
            out.append(text.content());
        }
        for (final Component child : component.children()) {
            collect(child, out);
        }
    }

    @Test
    void singleItemsGetNoCount() {
        final Component name = Component.text("Diamond Sword");

        assertEquals("Diamond Sword", plain(ShowService.withAmount(name, 1)));
        assertEquals(name, ShowService.withAmount(name, 1));
    }

    @Test
    void stacksShowTheirSize() {
        assertEquals("64x Diamond", plain(ShowService.withAmount(Component.text("Diamond"), 64)));
    }

    @Test
    void sizesAboveTheTooltipLimitStillShowTheRealCount() {
        assertEquals("150x Diamond", plain(ShowService.withAmount(Component.text("Diamond"), 150)));
    }

    @Test
    void theCountCarriesTheSameTooltipAsTheName() {
        final HoverEvent<?> tooltip = HoverEvent.showText(Component.text("+5 Attack Damage"));
        final Component name = Component.text("Diamond Sword").hoverEvent(tooltip);

        final Component result = ShowService.withAmount(name, 64);

        assertEquals("64x Diamond Sword", plain(result));
        assertEquals(tooltip, result.hoverEvent());
    }

    @Test
    void namesWithoutATooltipStillGetTheCount() {
        final Component result = ShowService.withAmount(Component.text("Cobblestone"), 32);

        assertEquals("32x Cobblestone", plain(result));
        assertNull(result.hoverEvent());
    }
}
