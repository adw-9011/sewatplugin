package io.github.near.sweats.scare;

import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Particle;

/**
 * One jumpscare: everything the victim sees and hears for a moment.
 *
 * @param id the name admins type after {@code /scare <player>}
 * @param displayName a friendly name shown in command feedback
 * @param title the big text slammed onto the screen
 * @param subtitle the line under it
 * @param sounds sounds played only to the victim
 * @param postEffects screen effects applied for {@code durationTicks}, then removed
 * @param particles particles spawned at the victim's eyes
 * @param durationTicks how long the post effects stay on screen
 */
public record ScarePreset(
        String id,
        String displayName,
        Component title,
        Component subtitle,
        List<SoundCue> sounds,
        List<Key> postEffects,
        List<ParticleBurst> particles,
        int durationTicks) {

    /** A sound plus the volume/pitch it should play at. */
    public record SoundCue(Key key, float volume, float pitch) {}

    /** A particle burst centred on the victim. */
    public record ParticleBurst(Particle particle, int count, double spread, double extra) {}

    public ScarePreset {
        sounds = List.copyOf(sounds);
        postEffects = List.copyOf(postEffects);
        particles = List.copyOf(particles);
    }

    /** Convenience factory for the built-in catalog. */
    static SoundCue sound(final String path, final float volume, final float pitch) {
        return new SoundCue(Key.key("minecraft", path), volume, pitch);
    }

    static ParticleBurst burst(final Particle particle, final int count, final double spread) {
        return new ParticleBurst(particle, count, spread, 0.02D);
    }
}
