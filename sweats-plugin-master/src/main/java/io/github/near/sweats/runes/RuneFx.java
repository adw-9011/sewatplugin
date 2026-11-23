package io.github.near.sweats.runes;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.inventory.ItemStack;

/** Small DSL for building the particle effects attached to runes. */
public final class RuneFx {

    private RuneFx() {}

    /** {@code loc} = where to play, {@code tier} = 1..3, {@code t} = animation counter. */
    @FunctionalInterface
    public interface Effect {
        void play(Location loc, int tier, int t);
    }

    private static void spawn(
            final Location location,
            final Particle particle,
            final int count,
            final double dx,
            final double dy,
            final double dz,
            final double speed,
            final Object data) {
        location.getWorld().spawnParticle(particle, location, count, dx, dy, dz, speed, data);
    }

    private static Location off(final Location location, final double x, final double y, final double z) {
        return location.clone().add(x, y, z);
    }

    /** Yaw-relative "right" vector. */
    private static double[] right(final Location location) {
        final double yaw = Math.toRadians(location.getYaw());
        return new double[] {-Math.cos(yaw), -Math.sin(yaw)};
    }

    /**
     * Creates data once and reuses it for every frame. Serverless tests can load the rune catalog
     * without constructing Bukkit item/block data eagerly.
     */
    private static <T> Supplier<T> lazily(final Supplier<T> factory) {
        return new Supplier<>() {
            private T value;

            @Override
            public T get() {
                if (value == null) {
                    value = factory.get();
                }
                return value;
            }
        };
    }

    public static Effect combo(final Effect... effects) {
        return (location, tier, tick) -> {
            for (final Effect effect : effects) {
                effect.play(location, tier, tick);
            }
        };
    }

    public static Effect puff(
            final Particle particle,
            final int count,
            final double spread,
            final double speed) {
        return (location, tier, tick) ->
                spawn(location, particle, count * tier, spread, spread, spread, speed, null);
    }

    public static Effect dust(
            final int red,
            final int green,
            final int blue,
            final float size,
            final int count,
            final double spread) {
        final Particle.DustOptions options =
                new Particle.DustOptions(Color.fromRGB(red, green, blue), size);
        return (location, tier, tick) ->
                spawn(location, Particle.DUST, count * tier, spread, spread, spread, 0, options);
    }

    /** Converts HSB (all 0..1) to a packed 0xRRGGBB value without pulling in AWT. */
    private static int hsbToRgb(final float hue, final float saturation, final float brightness) {
        final int sector = (int) (hue * 6) % 6;
        final float fraction = hue * 6 - (float) Math.floor(hue * 6);
        final float p = brightness * (1 - saturation);
        final float q = brightness * (1 - fraction * saturation);
        final float t = brightness * (1 - (1 - fraction) * saturation);
        final float red;
        final float green;
        final float blue;
        switch (sector) {
            case 0 -> {
                red = brightness;
                green = t;
                blue = p;
            }
            case 1 -> {
                red = q;
                green = brightness;
                blue = p;
            }
            case 2 -> {
                red = p;
                green = brightness;
                blue = t;
            }
            case 3 -> {
                red = p;
                green = q;
                blue = brightness;
            }
            case 4 -> {
                red = t;
                green = p;
                blue = brightness;
            }
            default -> {
                red = brightness;
                green = p;
                blue = q;
            }
        }
        return (Math.round(red * 255f) << 16)
                | (Math.round(green * 255f) << 8)
                | Math.round(blue * 255f);
    }

    public static Effect rainbow(final int count, final double spread, final float size) {
        return (location, tier, tick) -> {
            for (int index = 0; index < count * tier; index++) {
                final int rgb = hsbToRgb(((tick + index * 3) * 0.04f) % 1f, 1f, 1f);
                spawn(
                        location,
                        Particle.DUST,
                        1,
                        spread,
                        spread,
                        spread,
                        0,
                        new Particle.DustOptions(Color.fromRGB(rgb), size));
            }
        };
    }

    public static Effect block(final Material material, final int count, final double spread) {
        final Supplier<Object> data = lazily(material::createBlockData);
        return (location, tier, tick) -> spawn(
                location, Particle.BLOCK, count * tier, spread, spread, spread, 0.05, data.get());
    }

    public static Effect item(final Material material, final int count, final double spread) {
        final Supplier<ItemStack> item = lazily(() -> new ItemStack(material));
        return (location, tier, tick) -> spawn(
                location, Particle.ITEM, count * tier, spread, spread, spread, 0.04, item.get());
    }

    public static Effect spiral(
            final Particle particle, final double radius, final double height) {
        return (location, tier, tick) -> {
            for (int index = 0; index < 1 + tier; index++) {
                final double angle =
                        tick * 0.6 + index * Math.PI * 2 / (1 + tier);
                final double y = ((tick % 20) / 20.0) * height;
                spawn(
                        off(location, Math.cos(angle) * radius, y, Math.sin(angle) * radius),
                        particle,
                        1,
                        0,
                        0,
                        0,
                        0,
                        null);
            }
        };
    }

    public static Effect zigzag(final Particle particle) {
        return (location, tier, tick) -> {
            final double offset = Math.sin(tick * 0.9) * 0.35;
            final double[] vector = right(location);
            for (int index = 0; index < tier; index++) {
                spawn(
                        off(location, vector[0] * offset, 0.1, vector[1] * offset),
                        particle,
                        1,
                        0.02,
                        0.02,
                        0.02,
                        0,
                        null);
            }
        };
    }

    public static Effect zigzagDust(final int red, final int green, final int blue) {
        final Particle.DustOptions options =
                new Particle.DustOptions(Color.fromRGB(red, green, blue), 1.2f);
        return (location, tier, tick) -> {
            final double offset = Math.sin(tick * 0.9) * 0.35;
            final double[] vector = right(location);
            spawn(
                    off(location, vector[0] * offset, 0.1, vector[1] * offset),
                    Particle.DUST,
                    tier,
                    0.02,
                    0.02,
                    0.02,
                    0,
                    options);
        };
    }

    /** Expanding ring on the ground. */
    public static Effect wave(final Particle particle, final int points) {
        return (location, tier, tick) -> {
            final double radius = ((tick % 12) + 1) * 0.12 * (0.7 + tier * 0.3);
            for (int index = 0; index < points; index++) {
                final double angle = Math.PI * 2 * index / points;
                spawn(
                        off(location, Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius),
                        particle,
                        1,
                        0,
                        0,
                        0,
                        0,
                        null);
            }
        };
    }

    /** Rotating ring around the player, using a constant particle data object. */
    public static Effect orbit(
            final Particle particle,
            final double radius,
            final double y,
            final int points,
            final Object data) {
        return orbit(particle, radius, y, points, () -> data);
    }

    /** Rotating ring of item particles around the player. */
    public static Effect orbitItem(
            final Particle particle,
            final double radius,
            final double y,
            final int points,
            final Material material) {
        return orbit(particle, radius, y, points, lazily(() -> new ItemStack(material)));
    }

    /** Rotating ring of block particles around the player. */
    public static Effect orbitBlock(
            final Particle particle,
            final double radius,
            final double y,
            final int points,
            final Material material) {
        return orbit(particle, radius, y, points, lazily(material::createBlockData));
    }

    private static Effect orbit(
            final Particle particle,
            final double radius,
            final double y,
            final int points,
            final Supplier<Object> data) {
        return (location, tier, tick) -> {
            final Object particleData = data.get();
            for (int index = 0; index < points; index++) {
                final double angle = tick * 0.25 + Math.PI * 2 * index / points;
                spawn(
                        off(location, Math.cos(angle) * radius, y, Math.sin(angle) * radius),
                        particle,
                        1,
                        0,
                        0,
                        0,
                        0,
                        particleData);
            }
        };
    }

    public static Effect above(final Particle particle, final double height, final int count) {
        return (location, tier, tick) ->
                spawn(off(location, 0, height, 0), particle, count, 0.2, 0.1, 0.2, 0.01, null);
    }

    public static Effect aboveItem(final Material material, final double height) {
        final Supplier<ItemStack> item = lazily(() -> new ItemStack(material));
        return (location, tier, tick) ->
                spawn(off(location, 0, height, 0), Particle.ITEM, 2, 0.2, 0.1, 0.2, 0.01, item.get());
    }

    public static Effect crown(final int red, final int green, final int blue) {
        final Particle.DustOptions options =
                new Particle.DustOptions(Color.fromRGB(red, green, blue), 1f);
        return (location, tier, tick) -> {
            for (int index = 0; index < 8; index++) {
                final double angle = Math.PI * 2 * index / 8;
                spawn(
                        off(
                                location,
                                Math.cos(angle) * 0.3,
                                2.15 + (index % 2 == 0 ? 0.15 : 0),
                                Math.sin(angle) * 0.3),
                        Particle.DUST,
                        1,
                        0,
                        0,
                        0,
                        0,
                        options);
            }
        };
    }

    public static Effect antlers(final int red, final int green, final int blue) {
        final Particle.DustOptions options =
                new Particle.DustOptions(Color.fromRGB(red, green, blue), 1f);
        return (location, tier, tick) -> {
            final double[] vector = right(location);
            for (int side = -1; side <= 1; side += 2) {
                for (int index = 0; index < 5; index++) {
                    final double dx = vector[0] * side * (0.15 + index * 0.07);
                    final double dz = vector[1] * side * (0.15 + index * 0.07);
                    spawn(
                            off(location, dx, 2.0 + index * 0.12, dz),
                            Particle.DUST,
                            1,
                            0,
                            0,
                            0,
                            0,
                            options);
                }
            }
        };
    }

    /** Tail fan behind the player (turkey / peacock). */
    public static Effect fan(final int[][] colors) {
        return (location, tier, tick) -> {
            final int points = 7;
            for (int index = 0; index < points; index++) {
                final double angle = Math.toRadians(
                        location.getYaw() + 180 + (index - points / 2) * 22);
                final double length =
                        0.5 + 0.35 * (1 - Math.abs(index - points / 2) / (double) points);
                final int[] color = colors[index % colors.length];
                final Particle.DustOptions options =
                        new Particle.DustOptions(Color.fromRGB(color[0], color[1], color[2]), 1.2f);
                for (int step = 1; step <= 3; step++) {
                    final double radius = 0.25 + step * length / 3;
                    spawn(
                            off(
                                    location,
                                    -Math.sin(angle) * radius,
                                    1.0 + step * 0.18,
                                    Math.cos(angle) * radius),
                            Particle.DUST,
                            1,
                            0,
                            0,
                            0,
                            0,
                            options);
                }
            }
        };
    }

    /** Enchant glyphs that fly toward the wearer. */
    public static Effect enchantIn(final double radius) {
        return (location, tier, tick) -> {
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int index = 0; index < 2 * tier; index++) {
                final double angle = random.nextDouble() * Math.PI * 2;
                final double x = Math.cos(angle) * radius;
                final double z = Math.sin(angle) * radius;
                final double y = 0.5 + random.nextDouble() * 1.5;
                spawn(
                        off(location, x, y, z),
                        Particle.ENCHANT,
                        0,
                        -x,
                        1.2 - y,
                        -z,
                        1,
                        null);
            }
        };
    }

    /** Plays only on the first frame of a burst. */
    public static Effect sound(final String sound, final float pitch) {
        return (location, tier, tick) -> {
            if (tick == 0) {
                location.getWorld()
                        .playSound(location, sound, SoundCategory.PLAYERS, 0.6f, pitch);
            }
        };
    }

    public static Effect note() {
        return (location, tier, tick) -> {
            if (tick == 0) {
                final float pitch = 0.6f + ThreadLocalRandom.current().nextFloat() * 1.4f;
                location.getWorld()
                        .playSound(
                                location,
                                Sound.BLOCK_NOTE_BLOCK_HARP,
                                SoundCategory.PLAYERS,
                                0.7f,
                                pitch);
            }
            spawn(location, Particle.NOTE, 2 * tier, 0.4, 0.4, 0.4, 1, null);
        };
    }
}
