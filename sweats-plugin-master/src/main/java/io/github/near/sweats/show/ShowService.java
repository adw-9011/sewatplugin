package io.github.near.sweats.show;

import io.github.near.sweats.SweatsPlugin;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Turns {@code /show} requests into chat messages.
 *
 * <p>The item name is rendered with {@link ItemStack#displayName()}, which already carries the
 * vanilla item hover tooltip, so viewers can hover the name and inspect the exact item.
 */
public final class ShowService implements Listener {

    /** Vanilla only renders item tooltips for stack sizes up to 99. */
    private static final int MAX_TOOLTIP_AMOUNT = 99;

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final SweatsPlugin plugin;
    private final Map<UUID, Long> lastUsed = new HashMap<>();

    private volatile ShowSettings settings;

    public ShowService(final SweatsPlugin plugin) {
        this.plugin = plugin;
        this.settings = ShowSettings.from(plugin.getConfig());
    }

    /** Re-reads config and drops cooldown state so a reload takes effect immediately. */
    public void reloadSettings() {
        this.settings = ShowSettings.from(plugin.getConfig());
        this.lastUsed.clear();
    }

    public void clearState() {
        this.lastUsed.clear();
    }

    /**
     * Shows the item in the given slot.
     *
     * @return {@code true} when an item was actually shown, {@code false} when the slot was empty
     *     or the player was still on cooldown.
     */
    public boolean show(final Player player, final ShowSlot slot) {
        final ShowSettings current = settings;
        final ItemStack item = slot.itemIn(player);

        if (item == null || item.isEmpty()) {
            player.sendMessage(render(
                    current.emptyMessage(),
                    "Your " + slot.label() + " slot is empty.",
                    Placeholder.component("slot", Component.text(slot.label()))));
            return false;
        }

        final long remainingMillis = cooldownRemaining(player, current);
        if (remainingMillis > 0L) {
            final String seconds = String.format(Locale.ROOT, "%.1f", remainingMillis / 1000.0D);
            player.sendMessage(render(
                    current.cooldownMessage(),
                    "Try again in " + seconds + "s.",
                    Placeholder.unparsed("seconds", seconds)));
            return false;
        }

        lastUsed.put(player.getUniqueId(), System.currentTimeMillis());
        deliver(player, buildShowMessage(player, slot, item, current), current.radius());
        return true;
    }

    private Component buildShowMessage(
            final Player player, final ShowSlot slot, final ItemStack item, final ShowSettings current) {
        final ItemStack display = item.getAmount() > MAX_TOOLTIP_AMOUNT ? item.asOne() : item;

        return render(
                current.format(),
                player.getName() + " showed their " + slot.label() + ": " + display.getType(),
                Placeholder.component("player", player.displayName()),
                Placeholder.component("slot", Component.text(slot.label())),
                TagResolver.resolver("item", Tag.selfClosingInserting(
                        withAmount(display.displayName(), item.getAmount()))));
    }

    /**
     * Prefixes a stack size onto an item's name, e.g. {@code 64x Diamond}.
     *
     * <p>Only stacks of two or more get a count, so a lone sword still reads just as a sword. The
     * count carries the same hover tooltip as the name, so hovering the number behaves exactly
     * like hovering the name.
     */
    static Component withAmount(final Component itemName, final int amount) {
        if (amount <= 1) {
            return itemName;
        }

        final HoverEvent<?> tooltip = itemName.hoverEvent();
        final Component count = tooltip == null
                ? Component.text(amount + "x ")
                : Component.text(amount + "x ").hoverEvent(tooltip);
        return count.append(itemName);
    }

    /** Sends the message to the author, plus everyone close enough to see it. */
    private void deliver(final Player author, final Component message, final double radius) {
        author.sendMessage(message);
        if (radius <= 0.0D) {
            return;
        }

        final Location origin = author.getLocation();
        final double radiusSquared = radius * radius;
        for (final Player viewer : origin.getWorld().getPlayers()) {
            if (!viewer.equals(author) && viewer.getLocation().distanceSquared(origin) <= radiusSquared) {
                viewer.sendMessage(message);
            }
        }
    }

    /** Frees the cooldown entry as soon as a player leaves. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        lastUsed.remove(event.getPlayer().getUniqueId());
    }

    private long cooldownRemaining(final Player player, final ShowSettings current) {
        if (current.cooldownMillis() <= 0L || player.hasPermission("sweats.show.bypass")) {
            return 0L;
        }
        return remainingCooldown(player, current);
    }

    private long remainingCooldown(final Player player, final ShowSettings current) {
        final Long last = lastUsed.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        return last + current.cooldownMillis() - System.currentTimeMillis();
    }

    /**
     * Parses a MiniMessage template, falling back to plain text if an operator wrote something
     * that MiniMessage cannot parse.
     */
    private Component render(
            final String template, final String plainFallback, final TagResolver... resolvers) {
        try {
            return MINI_MESSAGE.deserialize(template, TagResolver.resolver(resolvers));
        } catch (final RuntimeException exception) {
            plugin.getLogger().warning("Invalid MiniMessage in config.yml: " + exception.getMessage());
            return Component.text(plainFallback);
        }
    }
}
