package io.github.near.sweats.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

class EmojisTest {

    private static String plain(final Component component) {
        final StringBuilder out = new StringBuilder();
        append(component, out);
        return out.toString();
    }

    private static void append(final Component component, final StringBuilder out) {
        if (component instanceof final TextComponent text) {
            out.append(text.content());
        }
        for (final Component child : component.children()) {
            append(child, out);
        }
    }

    private static String plainOf(final Emojis.Emoji emoji) {
        return plain(emoji.display());
    }

    @Test
    void replacesACodeInsideAMessage() {
        final Component result = Emojis.replace(Component.text("hi :dog: bye"));
        assertEquals("hi (\u1D54\u1D25\u1D54) bye", plain(result));
    }

    @Test
    void codesAreCaseInsensitive() {
        final Component result = Emojis.replace(Component.text(":STAR:"));
        assertEquals("\u272E", plain(result));
    }

    @Test
    void replacesEveryCodeInOneMessage() {
        final Component result = Emojis.replace(Component.text("<3 :yes: :no:"));
        assertEquals("\u2764 \u2714 \u2716", plain(result));
    }

    @Test
    void symbolCodesNeedToStandAlone() {
        assertEquals("hello/", plain(Emojis.replace(Component.text("hello/"))));
        assertEquals("go/no", plain(Emojis.replace(Component.text("go/no"))));
        assertEquals("a/o/b", plain(Emojis.replace(Component.text("a/o/b"))));
        assertFalse(plain(Emojis.replace(Component.text("o/"))).contains("o/"));
    }

    @Test
    void dabCodeBecomesTheDabFace() {
        assertEquals("<o/", plain(Emojis.replace(Component.text(":dab:"))));
    }

    @Test
    void leavesUnknownCodesAndPlainTextAlone() {
        assertEquals(":nothing: here", plain(Emojis.replace(Component.text(":nothing: here"))));
    }

    @Test
    void everyCodeIsUniqueAndHasADisplay() {
        final Set<String> seen = new HashSet<>();
        for (final Emojis.Emoji emoji : Emojis.all()) {
            assertTrue(seen.add(emoji.code().toLowerCase()), "duplicate code " + emoji.code());
            assertFalse(plainOf(emoji).isEmpty(), "empty display for " + emoji.code());
        }
    }

    @Test
    void rankLimitsSelectPrefixesOfTheRegistry() {
        assertEquals(Set.of("<3"), Emojis.codes(1));
        assertEquals(Set.of("<3", ":star:", ":yes:"), Emojis.codes(3));
        assertEquals(Emojis.all().size(), Emojis.codes(-1).size());
        assertEquals(Emojis.all().size(), Emojis.codes(9_999).size());
        assertEquals(Set.of(), Emojis.codes(0));
    }

    @Test
    void disallowedCodesStayLiteral() {
        final Component result =
                Emojis.replace(Component.text("<3 :star: :dog:"), Emojis.codes(1));

        assertEquals("\u2764 :star: :dog:", plain(result));
    }

    @Test
    void allowedCodesAreReplacedCaseInsensitively() {
        final Component result =
                Emojis.replace(Component.text(":STAR: <3"), Emojis.codes(3));

        assertEquals("\u272E \u2764", plain(result));
    }
}
