package io.github.near.sweats.scare;

import io.github.near.sweats.scare.ScarePreset.ParticleBurst;
import io.github.near.sweats.scare.ScarePreset.SoundCue;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Particle;

/** The built-in jumpscares available to {@code /scare}. */
public final class ScareCatalog {

    public static final String DEFAULT_ID = "jumpscare1";

    private static final List<ScarePreset> PRESETS = List.of(
            new ScarePreset(
                    "jumpscare1",
                    "Creeper",
                    Component.text("BOO!", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("you got jumped", NamedTextColor.DARK_RED),
                    List.of(
                            sound("entity.creeper.primed", 1.0F, 0.9F),
                            sound("entity.enderman.scream", 1.0F, 0.6F)),
                    List.of(effect("creeper")),
                    List.of(
                            burst(Particle.EXPLOSION_EMITTER, 1, 0.0D),
                            burst(Particle.LARGE_SMOKE, 30, 0.6D)),
                    30),

            new ScarePreset(
                    "jumpscare2",
                    "Glitch",
                    Component.text("STAY", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Component.text("don't look back", NamedTextColor.DARK_GRAY, TextDecoration.OBFUSCATED),
                    List.of(
                            sound("entity.warden.heartbeat", 1.4F, 0.8F),
                            sound("entity.warden.roar", 1.0F, 0.6F)),
                    List.of(effect("invert")),
                    List.of(
                            burst(Particle.SOUL, 40, 0.9D),
                            burst(Particle.ELECTRIC_SPARK, 25, 0.7D)),
                    40),

            new ScarePreset(
                    "jumpscare3",
                    "Spider",
                    Component.text("BEHIND YOU", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("eight legs", NamedTextColor.DARK_RED),
                    List.of(
                            sound("entity.spider.ambient", 1.4F, 0.5F),
                            sound("entity.spider.step", 1.2F, 0.5F)),
                    List.of(effect("spider")),
                    List.of(
                            burst(Particle.LARGE_SMOKE, 20, 0.5D),
                            burst(Particle.CRIT, 25, 0.8D)),
                    30),

            new ScarePreset(
                    "jumpscare4",
                    "Void",
                    Component.text("THE VOID", NamedTextColor.DARK_GRAY, TextDecoration.BOLD),
                    Component.text("it is quiet here", NamedTextColor.DARK_GRAY),
                    List.of(
                            sound("block.end_portal.spawn", 0.9F, 0.6F),
                            sound("ambient.cave", 1.0F, 0.7F)),
                    List.of(effect("blur")),
                    List.of(
                            burst(Particle.PORTAL, 60, 1.2D),
                            burst(Particle.SOUL_FIRE_FLAME, 15, 0.6D)),
                    60));

    private static final List<String> IDS = PRESETS.stream().map(ScarePreset::id).toList();

    private ScareCatalog() {}

    public static List<ScarePreset> all() {
        return PRESETS;
    }

    public static List<String> ids() {
        return IDS;
    }

    public static Optional<ScarePreset> byId(final String input) {
        final String needle = input.trim().toLowerCase(Locale.ROOT);
        return PRESETS.stream().filter(preset -> preset.id().equals(needle)).findFirst();
    }

    public static ScarePreset defaultPreset() {
        return byId(DEFAULT_ID).orElseThrow();
    }

    private static SoundCue sound(final String path, final float volume, final float pitch) {
        return ScarePreset.sound(path, volume, pitch);
    }

    private static ParticleBurst burst(final Particle particle, final int count, final double spread) {
        return ScarePreset.burst(particle, count, spread);
    }

    private static Key effect(final String path) {
        return Key.key("minecraft", path);
    }
}
