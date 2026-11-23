package io.github.near.sweats.chat;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The chat emojis: short codes such as {@code :dog:} or {@code <3} that become colored symbols.
 *
 * <p>Pure Adventure code with no server state, so it can be unit tested directly. Symbols are
 * written as unicode escapes so the build never depends on the compiler's source encoding.
 */
public final class Emojis {

    /** One emoji: the code players type and what it turns into. */
    public record Emoji(String code, Component display) {}

    private static final NamedTextColor RED = NamedTextColor.RED;
    private static final NamedTextColor GREEN = NamedTextColor.GREEN;
    private static final NamedTextColor YELLOW = NamedTextColor.YELLOW;
    private static final NamedTextColor GOLD = NamedTextColor.GOLD;
    private static final NamedTextColor AQUA = NamedTextColor.AQUA;
    private static final NamedTextColor PURPLE = NamedTextColor.LIGHT_PURPLE;
    private static final NamedTextColor BLUE = NamedTextColor.BLUE;

    private static final List<Emoji> ALL = List.of(
            emoji("<3", t("\u2764", RED)),
            emoji(":star:", t("\u272E", GOLD)),
            emoji(":yes:", t("\u2714", GREEN)),
            emoji(":no:", t("\u2716", RED)),
            emoji(":java:", t("\u2615", NamedTextColor.WHITE)),
            emoji(":arrow:", t("\u279C", YELLOW)),
            emoji(":shrug:", t("\u00AF\\_(\u30C4)_/\u00AF", YELLOW)),
            emoji(":tableflip:",
                    t("(\u256F\u00B0\u25A1\u00B0)\u256F\uFE35 ", RED),
                    t("\u253B\u2501\u253B", NamedTextColor.GRAY)),
            emoji("o/", t("(\uFF9F\u25E1\uFF9F)/", PURPLE)),
            emoji(":123:", t("1", GREEN), t("2", YELLOW), t("3", RED)),
            emoji(":totem:", t("\u2299_\u2299", AQUA)),
            emoji(":typing:", t("\u270E...", GOLD)),
            emoji(":maths:", t("\u221A(\u03C0+x)", GREEN), t("=L", RED)),
            emoji(":snail:", t("@'-'", YELLOW)),
            emoji(":thinking:", t("(", GREEN), t("0.o", GOLD), t("?)", GREEN)),
            emoji(":gimme:", t("\u0F3C\u3064\u25D5_\u25D5\u0F3D\u3064", AQUA)),
            emoji(":wizard:",
                    t("('-')\u2283\u2501", YELLOW),
                    t("\u2606", RED),
                    t("\uFF9F.*\uFF65\uFF61\uFF9F", PURPLE)),
            emoji(":pvp:", t("\u2694", YELLOW)),
            emoji(":peace:", t("\u270C", GOLD)),
            emoji(":oof:", Component.text("OOF", RED, TextDecoration.BOLD)),
            emoji(":puffer:", Component.text("<('O')>", YELLOW, TextDecoration.BOLD)),
            emoji(":yey:", t("\u30FD(\u25D5\u25E1\u25D5)\uFF89", GREEN)),
            emoji(":cat:", t("= ^\u25CF \u22CF \u25CF^ =", AQUA)),
            emoji(":dab:", t("<", PURPLE), t("o", YELLOW), t("/", PURPLE)),
            emoji(":dj:",
                    t("\u30FD", BLUE),
                    t("(\u2310", NamedTextColor.DARK_PURPLE),
                    t("\u25A0", RED),
                    t("_", NamedTextColor.GRAY),
                    t("\u25A0", YELLOW),
                    t(")", NamedTextColor.DARK_PURPLE),
                    t("\u30CE\u266C", BLUE)),
            emoji(":snow:", t("\u2603", AQUA)),
            emoji("^_^", t("^_^", GREEN)),
            emoji("h/", t("\u30FD(^\u25C7^*)/", GOLD)),
            emoji("^-^", t("^-^", GREEN)),
            emoji(":sloth:", t("( \u2022 \u2296 \u2022 )", GOLD)),
            emoji(":cute:", t("(", YELLOW), t("\u273F", GREEN), t("\u25E0\u203F\u25E0)", YELLOW)),
            emoji(":dog:", t("(\u1D54\u1D25\u1D54)", GOLD)));

    private static final Map<String, Component> BY_CODE = byCode();
    private static final Pattern PATTERN = buildPattern();

    private Emojis() {}

    /** Every emoji, in the order they are listed by {@code /emoji}. */
    public static List<Emoji> all() {
        return ALL;
    }

    /**
     * Returns a copy of {@code message} with every emoji code replaced.
     *
     * <p>Codes are matched case-insensitively. Letter-based codes such as {@code o/} and
     * {@code h/} only count when they stand alone, so "hello/" and "go/no" are left alone.
     */
    public static Component replace(final Component message) {
        return replace(message, (Set<String>) null);
    }

    /**
     * Returns a copy of {@code message} with the allowed emoji codes replaced. Codes that are not
     * in {@code allowedCodes} stay as literal text.
     */
    public static Component replace(final Component message, final Set<String> allowedCodes) {
        if (message == null) {
            return null;
        }
        return message.replaceText(TextReplacementConfig.builder()
                .match(PATTERN)
                .replacement((match, builder) -> {
                    final String code = match.group().toLowerCase(Locale.ROOT);
                    if (allowedCodes != null && !allowedCodes.contains(code)) {
                        return builder.build();
                    }
                    return BY_CODE.getOrDefault(code, builder.build());
                })
                .build());
    }

    /**
     * The first {@code limit} codes in registry order. A negative limit means every code.
     */
    public static Set<String> codes(final int limit) {
        final int count = limit < 0 ? ALL.size() : Math.min(limit, ALL.size());
        final Set<String> codes = new LinkedHashSet<>(count);
        for (final Emoji emoji : ALL.subList(0, count)) {
            codes.add(emoji.code().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(codes);
    }

    private static Emoji emoji(final String code, final Component... parts) {
        return new Emoji(code, Component.empty().children(List.of(parts)));
    }

    private static Component t(final String text, final NamedTextColor color) {
        return Component.text(text, color);
    }

    private static Map<String, Component> byCode() {
        final Map<String, Component> map = new HashMap<>();
        for (final Emoji emoji : ALL) {
            map.put(emoji.code().toLowerCase(Locale.ROOT), emoji.display());
        }
        return Map.copyOf(map);
    }

    private static Pattern buildPattern() {
        // Longest first so "<o/" wins over "o/" and "<3" never loses to a shorter code.
        final String alternation = ALL.stream()
                .map(Emoji::code)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return Pattern.compile(
                "(?<![A-Za-z0-9/])(?:" + alternation + ")(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE);
    }
}
