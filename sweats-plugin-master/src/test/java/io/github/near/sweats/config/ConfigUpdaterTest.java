package io.github.near.sweats.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ConfigUpdaterTest {

    private static final String BUNDLED = """
            # Sweats test config
            radius: 100000.0

            # /gif settings
            gifs:
              enabled: true
              folder: "gifs"

            mentions:
              # highlight colour
              color: yellow

            scares:
              # cooldown in seconds
              cooldown-seconds: 3.0
              sounds:
                - "entity.enderman.scream"
                - "ambient.cave"

            format: "<gray>x</gray>"
            """;

    private static Map<String, Object> parse(final String text) {
        final Object root = new Yaml().load(text);
        return ((Map<?, ?>) root).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()),
                        Map.Entry::getValue,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
    }

    @Test
    void anUpToDateFileIsLeftByteForByteAlone() {
        assertEquals(BUNDLED, ConfigUpdater.merge(BUNDLED, BUNDLED));
    }

    @Test
    void addsNewSectionsWithTheirComments() {
        final String merged = ConfigUpdater.merge(BUNDLED, "radius: 10.0\n");

        assertTrue(merged.contains("radius: 10.0"));
        assertTrue(merged.contains("# /gif settings"));
        assertTrue(merged.contains("gifs:"));
        assertTrue(merged.contains("  enabled: true"));
        assertTrue(merged.contains("folder: \"gifs\""));
        assertTrue(merged.contains("mentions:"));
    }

    @Test
    void addsAMissingKeyInsideAnExistingSection() {
        final String merged = ConfigUpdater.merge(BUNDLED, """
                mentions:
                  other: 1
                """);

        assertTrue(merged.contains("# highlight colour"));
        assertTrue(merged.contains("color: yellow"));
        assertFalse(merged.contains("other: 1"));
    }

    @Test
    void dropsKeysTheTemplateNoLongerHas() {
        final String merged = ConfigUpdater.merge(BUNDLED, """
                scares:
                  cooldown-seconds: 9.0
                  gifs:
                    enabled: false
                """);

        assertTrue(merged.contains("cooldown-seconds: 9.0"));
        assertFalse(merged.contains("enabled: false"));
    }

    @Test
    void keepsTheOwnersListValues() {
        final String merged = ConfigUpdater.merge(BUNDLED, """
                scares:
                  sounds:
                    - "custom:boo"
                """);

        assertTrue(merged.contains("custom:boo"));
        assertFalse(merged.contains("entity.enderman.scream"));
    }

    @Test
    void quotesValuesThatWouldOtherwiseChangeMeaning() {
        final String merged = ConfigUpdater.merge(BUNDLED, "format: \"a: b\"\n");

        assertEquals("a: b", parse(merged).get("format"));
    }

    @Test
    void writesMultiLineScalarsAsValidBlockScalars() {
        final String merged = ConfigUpdater.merge(BUNDLED, "format: \"first\\nsecond\"\n");

        assertEquals("first\nsecond", parse(merged).get("format"));
    }

    @Test
    void writesMultiLineListItemsAsValidBlockScalars() {
        final String merged = ConfigUpdater.merge(BUNDLED, """
                scares:
                  sounds:
                    - "one\\ntwo"
                """);

        final Map<?, ?> scares = (Map<?, ?>) parse(merged).get("scares");
        assertEquals(List.of("one\ntwo"), scares.get("sounds"));
    }

    @Test
    void keepsNumbersAsTheyWereTyped() {
        final String merged = ConfigUpdater.merge(BUNDLED, "radius: 42\n");

        assertEquals(42, parse(merged).get("radius"));
    }

    @Test
    void brokenYamlIsNeverTouched() {
        final String broken = "radius: [unclosed\n";

        assertEquals(broken, ConfigUpdater.merge(BUNDLED, broken));
    }

    @Test
    void movesTheOldSweatsNetPlaceholderToTheRealAddress() {
        final String bundled = """
                server:
                  name: "SURVIVAL MULTIPLAYER"
                  address: "sweat.playsmp.xyz"
                """;

        final String merged = ConfigUpdater.merge(
                bundled, "server:\n  address: \"Sweats.net\"\n");

        assertTrue(merged.contains("sweat.playsmp.xyz"));
        assertFalse(merged.contains("Sweats.net"));
    }

    @Test
    void leavesAnAddressTheOwnerTypedAlone() {
        final String bundled = """
                server:
                  address: "sweat.playsmp.xyz"
                """;

        final String merged = ConfigUpdater.merge(
                bundled, "server:\n  address: \"play.example.net\"\n");

        assertTrue(merged.contains("play.example.net"));
        assertFalse(merged.contains("sweat.playsmp.xyz"));
    }

    @Test
    void reportsWhichTopLevelSectionsChanged() {
        final ConfigUpdater.Update update = ConfigUpdater.Update.between(BUNDLED, "radius: 1.0\ngifs: {}\n");

        assertTrue(update.added().contains("mentions"));
        assertTrue(update.added().contains("scares"));
        assertFalse(update.added().contains("gifs"));
        assertTrue(update.changed());
    }
}
