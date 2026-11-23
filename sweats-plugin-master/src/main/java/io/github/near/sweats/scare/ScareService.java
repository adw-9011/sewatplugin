package io.github.near.sweats.scare;

import io.github.near.sweats.SweatsPlugin;
import io.github.near.sweats.scare.ScarePreset.ParticleBurst;
import io.github.near.sweats.scare.ScarePreset.SoundCue;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Applies jumpscares and cleans the screen effects back up afterwards.
 *
 * <p>The title, sounds and particles are one-shot; only the post effects ("creeper", "invert", ...)
 * linger, so they are tracked per player and removed when the scare ends, when the player leaves,
 * or when the plugin shuts down.
 */
public final class ScareService implements Listener {

    private static final long TICK_MILLIS = 50L;
    private static final long FADE_OUT_MILLIS = 250L;

    private final SweatsPlugin plugin;
    private final Map<UUID, Long> lastScared = new HashMap<>();
    private final Map<UUID, PostEffectSession> sessions = new HashMap<>();

    private volatile long cooldownMillis;

    public ScareService(final SweatsPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        this.cooldownMillis = Math.max(
                0L, Math.round(plugin.getConfig().getDouble("scares.cooldown-seconds", 3.0D) * 1000.0D));
        this.lastScared.clear();
    }

    /**
     * Drops all tracked state on disable.
     *
     * <p>Disabling cancels the pending cleanup tasks, so this is the last chance to take any
     * lingering screen distortion off the players who are mid-scare right now.
     */
    public void clearState() {
        this.lastScared.clear();
        sessions.forEach((uuid, session) -> {
            session.cleanup().cancel();
            final Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                session.effects().forEach(effect -> safeRemove(player, effect));
            }
        });
        this.sessions.clear();
    }

    /**
     * Scares the target if the cooldown allows it.
     *
     * @param bypassCooldown whether the <em>sender</em> may ignore the cooldown
     * @return {@code 0} when the scare was applied, otherwise the milliseconds left on cooldown
     */
    public long scare(final Player target, final ScarePreset preset, final boolean bypassCooldown) {
        final long remaining = remainingCooldown(target, bypassCooldown);
        if (remaining > 0L) {
            return remaining;
        }

        lastScared.put(target.getUniqueId(), System.currentTimeMillis());
        apply(target, preset);
        return 0L;
    }

    private void apply(final Player target, final ScarePreset preset) {
        target.showTitle(Title.title(
                preset.title(),
                preset.subtitle(),
                Title.Times.times(Duration.ZERO, stayDuration(preset.durationTicks()), Duration.ofMillis(FADE_OUT_MILLIS))));

        for (final SoundCue cue : preset.sounds()) {
            target.playSound(Sound.sound(cue.key(), Sound.Source.MASTER, cue.volume(), cue.pitch()));
        }

        final Location eyes = target.getEyeLocation();
        for (final ParticleBurst burst : preset.particles()) {
            target.getWorld().spawnParticle(
                    burst.particle(),
                    eyes,
                    burst.count(),
                    burst.spread(),
                    burst.spread(),
                    burst.spread(),
                    burst.extra());
        }

        applyPostEffects(target, preset);
    }

    private void applyPostEffects(final Player target, final ScarePreset preset) {
        final UUID id = target.getUniqueId();
        final PostEffectSession previous = sessions.remove(id);
        if (previous != null) {
            previous.cleanup().cancel();
        }

        final List<Key> effects = List.copyOf(preset.postEffects());
        final Set<Key> desired = new HashSet<>(effects);

        if (previous != null) {
            for (final Key effect : previous.effects()) {
                if (!desired.contains(effect)) {
                    safeRemove(target, effect);
                }
            }
        }

        effects.forEach(effect -> safeAdd(target, effect));
        if (effects.isEmpty()) {
            return;
        }

        final BukkitTask cleanup = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            final PostEffectSession current = sessions.get(id);
            if (current == null || current.effects() != effects) {
                return;
            }
            sessions.remove(id);
            if (target.isOnline()) {
                effects.forEach(effect -> safeRemove(target, effect));
            }
        }, preset.durationTicks());

        sessions.put(id, new PostEffectSession(effects, cleanup));
    }

    private void safeAdd(final Player target, final Key effect) {
        try {
            target.postEffects().add(effect);
        } catch (final RuntimeException ex) {
            plugin.getLogger().warning("Could not apply post effect '" + effect.asString() + "': " + ex.getMessage());
        }
    }

    private void safeRemove(final Player target, final Key effect) {
        try {
            target.postEffects().remove(effect);
        } catch (final RuntimeException ex) {
            plugin.getLogger().warning("Could not remove post effect '" + effect.asString() + "': " + ex.getMessage());
        }
    }

    /** Frees tracking state and cancels pending cleanup when a player leaves mid-scare. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        lastScared.remove(id);

        final PostEffectSession session = sessions.remove(id);
        if (session == null) {
            return;
        }
        session.cleanup().cancel();
        session.effects().forEach(effect -> safeRemove(event.getPlayer(), effect));
    }

    /** How long the title body stays on screen: the full scare minus the fade-out. */
    static Duration stayDuration(final int durationTicks) {
        return Duration.ofMillis(Math.max(0L, durationTicks * TICK_MILLIS - FADE_OUT_MILLIS));
    }

    /** Milliseconds left on a cooldown started at {@code lastMillis}, never negative. */
    static long remainingCooldown(final long lastMillis, final long nowMillis, final long cooldownMillis) {
        return Math.max(0L, lastMillis + cooldownMillis - nowMillis);
    }

    private long remainingCooldown(final Player target, final boolean bypassCooldown) {
        if (cooldownMillis <= 0L || bypassCooldown) {
            return 0L;
        }

        final Long last = lastScared.get(target.getUniqueId());
        if (last == null) {
            return 0L;
        }
        return remainingCooldown(last, System.currentTimeMillis(), cooldownMillis);
    }

    private record PostEffectSession(List<Key> effects, BukkitTask cleanup) {}
}
