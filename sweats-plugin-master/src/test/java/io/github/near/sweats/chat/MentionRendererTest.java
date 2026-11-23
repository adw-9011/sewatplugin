package io.github.near.sweats.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class MentionRendererTest {

    private record Segment(String text, TextColor color) {}

    private static List<Segment> segments(final Component component) {
        final List<Segment> out = new ArrayList<>();
        collect(component, null, out);
        return out;
    }

    private static void collect(final Component component, final TextColor inherited, final List<Segment> out) {
        final TextColor color = component.color() != null ? component.color() : inherited;
        if (component instanceof final TextComponent text && !text.content().isEmpty()) {
            out.add(new Segment(text.content(), color));
        }
        for (final Component child : component.children()) {
            collect(child, color, out);
        }
    }

    private static String plain(final Component component) {
        return String.join("", segments(component).stream().map(Segment::text).toList());
    }

    @Test
    void highlightsOnlyTheNameAndKeepsTheRestUntouched() {
        final Component input = Component.text("Player1 yoo wsp", NamedTextColor.GRAY);

        final Component result = MentionRenderer.highlight(input, List.of("Player1"), NamedTextColor.YELLOW);

        assertEquals("Player1 yoo wsp", plain(result));
        assertEquals(List.of(
                new Segment("Player1", NamedTextColor.YELLOW),
                new Segment(" yoo wsp", NamedTextColor.GRAY)), segments(result));
    }

    @Test
    void matchesCaseInsensitivelyButKeepsWhatWasTyped() {
        final Component result = MentionRenderer.highlight(
                Component.text("yo plAyEr1 hi", NamedTextColor.WHITE), List.of("Player1"), NamedTextColor.YELLOW);

        assertEquals("yo plAyEr1 hi", plain(result));
        assertEquals(new Segment("plAyEr1", NamedTextColor.YELLOW), segments(result).get(1));
    }

    @Test
    void doesNotMatchInsideLongerWords() {
        final Component result = MentionRenderer.highlight(
                Component.text("xPlayer1 Player123 Player1_", NamedTextColor.GRAY),
                List.of("Player1"), NamedTextColor.YELLOW);

        assertEquals(List.of(new Segment("xPlayer1 Player123 Player1_", NamedTextColor.GRAY)), segments(result));
    }

    @Test
    void highlightsAtMentions() {
        final Component result = MentionRenderer.highlight(
                Component.text("@Player1 look", NamedTextColor.GRAY), List.of("Player1"), NamedTextColor.YELLOW);

        assertEquals(List.of(
                new Segment("@Player1", NamedTextColor.YELLOW),
                new Segment(" look", NamedTextColor.GRAY)), segments(result));
    }

    @Test
    void prefersTheLongestMatchingName() {
        final Component result = MentionRenderer.highlight(
                Component.text("Bobby and Bob", NamedTextColor.GRAY),
                List.of("Bob", "Bobby"), NamedTextColor.YELLOW);

        assertEquals(List.of(
                new Segment("Bobby", NamedTextColor.YELLOW),
                new Segment(" and ", NamedTextColor.GRAY),
                new Segment("Bob", NamedTextColor.YELLOW)), segments(result));
    }

    @Test
    void leavesMessagesAloneWhenThereIsNothingToHighlight() {
        final Component input = Component.text("hello world", NamedTextColor.GRAY);

        assertEquals(input, MentionRenderer.highlight(input, List.of(), NamedTextColor.YELLOW));
        assertEquals(input, MentionRenderer.highlight(input, (Pattern) null, NamedTextColor.YELLOW));
    }

    @Test
    void treatsRegexCharactersInNamesLiterally() {
        final Component result = MentionRenderer.highlight(
                Component.text("hey a.b*c", NamedTextColor.GRAY), List.of("a.b*c", "aXbYc"), NamedTextColor.YELLOW);

        assertEquals(new Segment("a.b*c", NamedTextColor.YELLOW), segments(result).get(1));
    }

    @Test
    void parsesConfiguredColors() {
        assertEquals(NamedTextColor.GOLD, MentionSettings.parseColor("gold", NamedTextColor.YELLOW));
        assertEquals(NamedTextColor.YELLOW, MentionSettings.parseColor("not-a-color", NamedTextColor.YELLOW));
        assertEquals(TextColor.color(0xFFD700), MentionSettings.parseColor("#ffd700", NamedTextColor.YELLOW));
        assertEquals(NamedTextColor.YELLOW, MentionSettings.parseColor("  ", NamedTextColor.YELLOW));
    }
}
