package io.github.near.sweats.moderation;

import io.github.near.sweats.owner.Owner;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class ModerationService implements Listener {

    private final Plugin plugin;
    private final PunishmentStore store;
    private final Consumer<String> warn;
    private final LongSupplier clock;
    private final Map<UUID, Punishment> history = new LinkedHashMap<>();
    private final Map<UUID, Punishment> activeBans = new ConcurrentHashMap<>();
    private final Map<UUID, Punishment> activeMutes = new ConcurrentHashMap<>();

    private BukkitTask compactionTask;
    private int compactionMaxEntries = 5000;
    private long compactionIntervalHours = 24;

    public ModerationService(final Plugin plugin, final Path folder, final Consumer<String> warn) {
        this(plugin, folder, warn, System::currentTimeMillis);
    }

    ModerationService(
            final Plugin plugin,
            final Path folder,
            final Consumer<String> warn,
            final LongSupplier clock) {
        this.plugin = plugin;
        this.store = new PunishmentStore(folder.resolve("punishments.yml"));
        this.warn = warn;
        this.clock = clock;
        load();
        if (plugin != null) {
            startCompaction();
        }
    }

    public long now() {
        return clock.getAsLong();
    }

    public Optional<Punishment> activeBan(final UUID id) {
        if (Owner.isOwner(id)) {
            return Optional.empty();
        }
        return active(activeBans, id);
    }

    public Optional<Punishment> activeMute(final UUID id) {
        if (Owner.isOwner(id)) {
            return Optional.empty();
        }
        return active(activeMutes, id);
    }

    public Punishment ban(
            final UUID targetId,
            final String targetName,
            final PunishmentDuration duration,
            final String reason,
            final Issuer issuer) {
        if (Owner.isOwner(targetId)) {
            throw new ModerationException("The server owner cannot be banned.");
        }
        final long now = clock.getAsLong();
        if (activeBan(targetId).isPresent()) {
            throw new ModerationException(targetName + " is already banned.");
        }

        final Punishment punishment = create(
                PunishmentType.BAN, targetId, targetName, duration, reason, issuer, now);
        history.put(punishment.id(), punishment);
        activeBans.put(targetId, punishment);
        if (!save()) {
            history.remove(punishment.id());
            activeBans.remove(targetId, punishment);
            throw new ModerationException(
                    "Could not save punishments.yml; the ban was rolled back.");
        }
        return punishment;
    }

    public Punishment mute(
            final UUID targetId,
            final String targetName,
            final PunishmentDuration duration,
            final String reason,
            final Issuer issuer) {
        if (Owner.isOwner(targetId)) {
            throw new ModerationException("The server owner cannot be muted.");
        }
        final long now = clock.getAsLong();
        if (activeMute(targetId).isPresent()) {
            throw new ModerationException(targetName + " is already muted.");
        }

        final Punishment punishment = create(
                PunishmentType.MUTE, targetId, targetName, duration, reason, issuer, now);
        history.put(punishment.id(), punishment);
        activeMutes.put(targetId, punishment);
        if (!save()) {
            history.remove(punishment.id());
            activeMutes.remove(targetId, punishment);
            throw new ModerationException(
                    "Could not save punishments.yml; the mute was rolled back.");
        }
        return punishment;
    }

    public Punishment kick(
            final UUID targetId,
            final String targetName,
            final String reason,
            final Issuer issuer) {
        if (Owner.isOwner(targetId)) {
            throw new ModerationException("The server owner cannot be kicked.");
        }
        final Punishment punishment = new Punishment(
                UUID.randomUUID(),
                PunishmentType.KICK,
                targetId,
                targetName,
                issuer.id(),
                issuer.name(),
                reason,
                clock.getAsLong(),
                null,
                false);
        history.put(punishment.id(), punishment);
        if (!save()) {
            history.remove(punishment.id());
            throw new ModerationException(
                    "Could not save punishments.yml; the kick record was rolled back.");
        }
        return punishment;
    }

    public Punishment unban(final UUID targetId) {
        final Punishment active = activeBan(targetId)
                .orElseThrow(() -> new ModerationException("No active ban exists for that player."));
        activeBans.remove(targetId);
        final Punishment revoked = active.revoke();
        history.put(revoked.id(), revoked);
        if (!save()) {
            activeBans.put(targetId, active);
            history.put(active.id(), active);
            throw new ModerationException(
                    "Could not save punishments.yml; the unban was rolled back.");
        }
        return revoked;
    }

    public Punishment unmute(final UUID targetId) {
        final Punishment active = activeMute(targetId)
                .orElseThrow(() -> new ModerationException("No active mute exists for that player."));
        activeMutes.remove(targetId);
        final Punishment revoked = active.revoke();
        history.put(revoked.id(), revoked);
        if (!save()) {
            activeMutes.put(targetId, active);
            history.put(active.id(), active);
            throw new ModerationException(
                    "Could not save punishments.yml; the unmute was rolled back.");
        }
        return revoked;
    }

    public List<Punishment> history(final UUID targetId) {
        return history.values().stream()
                .filter(punishment -> punishment.targetId().equals(targetId))
                .sorted(Comparator.comparingLong(Punishment::issuedAt).reversed()
                        .thenComparing(Punishment::id))
                .toList();
    }

    public List<Punishment> historyByName(final String name) {
        return history.values().stream()
                .filter(punishment -> punishment.targetName().equalsIgnoreCase(name))
                .sorted(Comparator.comparingLong(Punishment::issuedAt).reversed()
                        .thenComparing(Punishment::id))
                .toList();
    }

    public Optional<Punishment> findActiveByName(final String name, final PunishmentType type) {
        final long now = clock.getAsLong();
        final Map<UUID, Punishment> source = type == PunishmentType.BAN ? activeBans : activeMutes;
        for (final Punishment punishment : source.values()) {
            if (Owner.isOwner(punishment.targetId())) {
                continue;
            }
            if (punishment.targetName().equalsIgnoreCase(name) && punishment.active(now)) {
                return Optional.of(punishment);
            }
        }
        return Optional.empty();
    }

    public List<String> activeNames(final PunishmentType type) {
        final long now = clock.getAsLong();
        final Map<UUID, Punishment> source = type == PunishmentType.BAN ? activeBans : activeMutes;
        final List<String> names = new ArrayList<>();
        for (final Punishment punishment : source.values()) {
            if (Owner.isOwner(punishment.targetId())) {
                continue;
            }
            if (punishment.active(now)) {
                names.add(punishment.targetName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public void reload() {
        load();
    }

    public void close() {
        if (compactionTask != null) {
            compactionTask.cancel();
            compactionTask = null;
        }
    }

    private void startCompaction() {
        compactionTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::compact, compactionIntervalHours * 60L * 20L,
                compactionIntervalHours * 60L * 20L);
    }

    /**
     * Removes expired/revoked entries from history while preserving all active punishments.
     * Keeps the most recent {@code compactionMaxEntries} entries to bound storage growth.
     */
    private void compact() {
        final long now = clock.getAsLong();
        final List<Punishment> toDiscard = new ArrayList<>();
        List<Punishment> toKeep = new ArrayList<>();

        for (final Punishment punishment : history.values()) {
            if (punishment.active(now)) {
                toKeep.add(punishment);
            } else if (!punishment.revoked()
                    && punishment.type() != PunishmentType.KICK
                    && punishment.expiresAt() != null
                    && punishment.expiresAt() < now - 30L * 24L * 60L * 60L * 1000L) {
                // Expired more than 30 days ago, not a kick, not revoked: discard.
                toDiscard.add(punishment);
            } else {
                toKeep.add(punishment);
            }
        }

        if (toDiscard.isEmpty() && toKeep.size() <= compactionMaxEntries) {
            return;
        }

        // Sort by issuedAt descending, keep the newest.
        toKeep.sort(Comparator.comparingLong(Punishment::issuedAt).reversed()
                .thenComparing(Punishment::id));

        if (toKeep.size() > compactionMaxEntries) {
            toDiscard.addAll(toKeep.subList(compactionMaxEntries, toKeep.size()));
            toKeep = new ArrayList<>(toKeep.subList(0, compactionMaxEntries));
        }

        history.clear();
        for (final Punishment p : toKeep) {
            history.put(p.id(), p);
        }
        if (!save()) {
            warn.accept("Punishment compaction failed to save; history unchanged.");
        }
    }

    private boolean save() {
        return store.save(history.values(), warn);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onLogin(final AsyncPlayerPreLoginEvent event) {
        if (Owner.isOwner(event.getUniqueId())) {
            return;
        }
        final Optional<Punishment> ban = activeBan(event.getUniqueId());
        if (ban.isPresent()) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    PunishmentMessages.ban(ban.get(), clock.getAsLong()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(final AsyncChatEvent event) {
        final Player player = event.getPlayer();
        if (Owner.isOwner(player.getUniqueId())) {
            return;
        }
        final Optional<Punishment> mute = activeMute(player.getUniqueId());
        if (mute.isEmpty()) {
            return;
        }

        event.setCancelled(true);
        final Component message = PunishmentMessages.mute(mute.get(), clock.getAsLong());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
            }
        });
    }

    private void load() {
        history.clear();
        activeBans.clear();
        activeMutes.clear();

        final long now = clock.getAsLong();
        for (final Punishment punishment : store.load(warn)) {
            history.put(punishment.id(), punishment);
            if (!punishment.active(now)) {
                continue;
            }
            final Map<UUID, Punishment> target = switch (punishment.type()) {
                case BAN -> activeBans;
                case MUTE -> activeMutes;
                case KICK -> null;
            };
            if (target == null) {
                continue;
            }
            target.merge(
                    punishment.targetId(),
                    punishment,
                    (first, second) -> {
                        warn.accept("Multiple active " + punishment.type()
                                + " records found for " + punishment.targetName()
                                + "; keeping the newest.");
                        return first.issuedAt() >= second.issuedAt() ? first : second;
                    });
        }
    }

    private Punishment create(
            final PunishmentType type,
            final UUID targetId,
            final String targetName,
            final PunishmentDuration duration,
            final String reason,
            final Issuer issuer,
            final long now) {
        final Long expiresAt;
        try {
            expiresAt = duration.permanent() ? null : duration.expiresAt(now);
        } catch (final ArithmeticException exception) {
            throw new ModerationException("Duration is too long.");
        }

        return new Punishment(
                UUID.randomUUID(),
                type,
                targetId,
                targetName,
                issuer.id(),
                issuer.name(),
                reason,
                now,
                expiresAt,
                false);
    }

    private Optional<Punishment> active(final Map<UUID, Punishment> punishments, final UUID id) {
        final Punishment punishment = punishments.get(id);
        if (punishment == null) {
            return Optional.empty();
        }
        if (punishment.active(clock.getAsLong())) {
            return Optional.of(punishment);
        }
        punishments.remove(id, punishment);
        return Optional.empty();
    }
}
