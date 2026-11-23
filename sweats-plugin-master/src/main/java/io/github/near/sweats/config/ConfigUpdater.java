package io.github.near.sweats.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps an installed {@code config.yml} in step with the one shipped inside the jar.
 *
 * <p>On every start (and every {@code /sweats reload}) the bundled file is used as the layout: its
 * keys and comments are written out in its order, and any value the owner already set is carried
 * over. That means a plugin update adds the new settings with their documentation, drops settings
 * that no longer exist, and never resets a tuned value - without anyone copying files by hand.
 *
 * <p>When the result differs from what is on disk the old file is kept as {@code config.yml.bak}
 * and the owner is told in the log. A file that is not valid YAML is left completely alone.
 */
public final class ConfigUpdater {

    private static final Yaml YAML = new Yaml();

    private ConfigUpdater() {}

    /**
     * Rewrites the plugin's config file from the bundled one, keeping the owner's values.
     *
     * @return the names of the top-level sections that were added or removed, for logging
     */
    public static Update update(final JavaPlugin plugin) {
        final Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        final String bundled = readResource(plugin);
        if (bundled == null) {
            return Update.NONE;
        }

        final String existing;
        try {
            existing = Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            plugin.getLogger().warning("Could not read " + file + ": " + exception.getMessage());
            return Update.NONE;
        }

        final String merged = merge(bundled, existing);
        if (merged.equals(existing)) {
            return Update.NONE;
        }

        try {
            Files.copy(file, file.resolveSibling("config.yml.bak"), StandardCopyOption.REPLACE_EXISTING);
            writeAtomically(file, merged);
            plugin.reloadConfig();
        } catch (final IOException exception) {
            plugin.getLogger().warning("Could not update " + file + ": " + exception.getMessage());
            return Update.NONE;
        }

        return Update.between(bundled, existing);
    }

    private static void writeAtomically(final Path file, final String text) throws IOException {
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException exception) {
            try {
                Files.deleteIfExists(temp);
            } catch (final IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    /**
     * Builds the updated config text.
     *
     * <p>Only insertion and removal of keys happens on top of the bundled layout; existing lines
     * are reused verbatim whenever the value did not change, so comments and formatting stay tidy.
     *
     * @param bundled the config.yml from the jar, which defines the keys and their comments
     * @param existing what is on disk right now
     * @return the text that should be on disk; {@code existing} itself when it cannot be parsed
     */
    public static String merge(final String bundled, final String existing) {
        final Map<String, Object> values = parse(existing);
        if (values == null) {
            return existing;
        }
        migrate(values);

        final List<String> lines = bundled.lines().toList();
        final List<String> out = new ArrayList<>(lines.size() + 8);
        render(lines, 0, lines.size(), 0, List.of(), values, out);
        return String.join("\n", out) + "\n";
    }

    /**
     * Replaces a value that was only ever the shipped default in an older build.
     *
     * <p>The comparison is exact, so an address the owner actually typed is never touched. This
     * is what moves the old {@code Sweats.net} placeholder to the real server address without
     * asking anyone to edit the file by hand.
     */
    private static void migrate(final Map<String, Object> values) {
        replaceOldDefault(values, "server", "address", "Sweats.net", "sweat.playsmp.xyz");
    }

    private static void replaceOldDefault(
            final Map<String, Object> values,
            final String section,
            final String key,
            final String oldValue,
            final String newValue) {
        if (!(values.get(section) instanceof final Map<?, ?> raw)) {
            return;
        }
        if (!oldValue.equals(raw.get(key))) {
            return;
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> mutable = (Map<String, Object>) raw;
        mutable.put(key, newValue);
    }

    /** Which top-level sections changed, so the log line can name them. */
    public record Update(Set<String> added, Set<String> removed) {

        static final Update NONE = new Update(Set.of(), Set.of());

        static Update between(final String bundled, final String existing) {
            final Map<String, Object> before = parse(existing);
            final Map<String, Object> after = parse(bundled);
            if (before == null || after == null) {
                return NONE;
            }

            final Set<String> added = new LinkedHashSet<>(after.keySet());
            added.removeAll(before.keySet());
            final Set<String> removed = new LinkedHashSet<>(before.keySet());
            removed.removeAll(after.keySet());
            return new Update(added, removed);
        }

        public boolean changed() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rendering the bundled layout with the owner's values

    private static void render(
            final List<String> lines,
            final int from,
            final int to,
            final int indent,
            final List<String> path,
            final Map<String, Object> values,
            final List<String> out) {
        int index = from;
        while (index < to) {
            final String line = lines.get(index);
            final String body = line.stripLeading();

            if (body.isEmpty() || body.startsWith("#") || indentOf(line) != indent) {
                out.add(line);
                index++;
                continue;
            }

            final int colon = body.indexOf(':');
            if (colon < 0) {
                out.add(line);
                index++;
                continue;
            }

            final String key = body.substring(0, colon).trim();
            final String inline = body.substring(colon + 1).trim();
            final List<String> keyPath = append(path, key);

            // Treat lines with only a comment after the colon as block mappings, not scalars
            final boolean hasInlineValue = !inline.isEmpty() && !inline.startsWith("#");

            if (hasInlineValue) {
                out.add(rewriteScalar(line, indent, key, inline, values, keyPath));
                index++;
                continue;
            }

            final int end = blockEnd(lines, index + 1, to, indent);
            final Object value = lookup(values, keyPath);

            if (isList(lines, index + 1, end)) {
                renderList(lines, index, end, key, indent, value, out);
            } else {
                out.add(line);
                final int childIndent = firstChildIndent(lines, index + 1, end, indent);
                if (childIndent > indent) {
                    render(lines, index + 1, end, childIndent, keyPath, values, out);
                }
            }
            index = end;
        }
    }

    /** Writes the bundled line, or the owner's value when it differs from the bundled default. */
    private static String rewriteScalar(
            final String line,
            final int indent,
            final String key,
            final String inline,
            final Map<String, Object> values,
            final List<String> path) {
        final Object value = lookup(values, path);
        if (value == null || sameValue(parseInline(inline), value)) {
            return line;
        }
        return spaces(indent) + key + ": " + inlineValue(value, indent + 2);
    }

    /**
     * Replaces the whole list block when the owner changed it; otherwise keeps the bundled text so
     * quoting and comments stay exactly as shipped.
     */
    private static void renderList(
            final List<String> lines,
            final int keyIndex,
            final int end,
            final String key,
            final int indent,
            final Object value,
            final List<String> out) {
        if (!(value instanceof List<?> list) || list.equals(parseList(lines, keyIndex + 1, end, key))) {
            out.addAll(lines.subList(keyIndex, end));
            return;
        }

        out.add(lines.get(keyIndex));
        for (final Object item : list) {
            out.add(spaces(indent + 2) + "- " + inlineValue(item, indent + 4));
        }
    }

    /** Parses a bundled list block into a list, so it can be compared with the owner's value. */
    private static List<?> parseList(
            final List<String> lines, final int from, final int to, final String key) {
        final StringBuilder text = new StringBuilder(key).append(":\n");
        for (int index = from; index < to; index++) {
            final String body = lines.get(index).stripLeading();
            if (!body.isEmpty() && !body.startsWith("#")) {
                text.append("  ").append(body).append('\n');
            }
        }

        final Object parsed = YAML.load(text.toString());
        if (parsed instanceof final Map<?, ?> map && map.get(key) instanceof final List<?> list) {
            return list;
        }
        return List.of();
    }

    /** End of a key's child block: the first non-blank line indented no deeper than the key. */
    private static int blockEnd(
            final List<String> lines, final int from, final int to, final int indent) {
        int end = from;
        while (end < to) {
            final String line = lines.get(end);
            if (!line.isBlank() && indentOf(line) <= indent) {
                break;
            }
            end++;
        }
        return end;
    }

    /** True when the block holds {@code - item} entries rather than nested keys. */
    private static boolean isList(final List<String> lines, final int from, final int to) {
        for (int index = from; index < to; index++) {
            final String body = lines.get(index).stripLeading();
            if (body.isEmpty() || body.startsWith("#")) {
                continue;
            }
            return body.equals("-") || body.startsWith("- ");
        }
        return false;
    }

    /** Indentation of the first real (non-comment) child, or the parent indent when there is none. */
    private static int firstChildIndent(
            final List<String> lines, final int from, final int to, final int fallback) {
        for (int index = from; index < to; index++) {
            final String body = lines.get(index).stripLeading();
            if (!body.isEmpty() && !body.startsWith("#")) {
                return indentOf(lines.get(index));
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------------------------------
    // YAML helpers

    private static Map<String, Object> parse(final String text) {
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            final Object root = YAML.load(text);
            if (!(root instanceof final Map<?, ?> map)) {
                return null;
            }
            final Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
            return values;
        } catch (final RuntimeException exception) {
            return null;
        }
    }

    private static Object lookup(final Map<String, Object> values, final List<String> path) {
        Object current = values;
        for (final String key : path) {
            if (!(current instanceof final Map<?, ?> map) || !map.containsKey(key)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private static Object parseInline(final String text) {
        try {
            final Object parsed = YAML.load("value: " + text);
            return parsed instanceof final Map<?, ?> map ? map.get("value") : null;
        } catch (final RuntimeException exception) {
            return null;
        }
    }

    private static boolean sameValue(final Object left, final Object right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        return left instanceof final Number a
                && right instanceof final Number b
                && Double.compare(a.doubleValue(), b.doubleValue()) == 0;
    }

    /** One YAML scalar, using a block scalar when the value contains line breaks. */
    private static String inlineValue(final Object value, final int contentIndent) {
        final String dumped = YAML.dump(value).trim();
        if (!dumped.contains("\n")) {
            return dumped;
        }

        final StringBuilder out = new StringBuilder("|-");
        for (final String line : String.valueOf(value).split("\n", -1)) {
            out.append('\n').append(spaces(contentIndent)).append(line);
        }
        return out.toString();
    }

    private static List<String> append(final List<String> path, final String key) {
        final List<String> next = new ArrayList<>(path.size() + 1);
        next.addAll(path);
        next.add(key);
        return List.copyOf(next);
    }

    private static int indentOf(final String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    private static String spaces(final int count) {
        return " ".repeat(count);
    }

    private static String readResource(final JavaPlugin plugin) {
        try (InputStream stream = plugin.getResource("config.yml")) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            plugin.getLogger().warning("Could not read the bundled config.yml: " + exception.getMessage());
            return null;
        }
    }
}
