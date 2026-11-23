package io.github.near.sweats.dupe;

import io.github.near.sweats.logging.discord.DiscordLogService;
import io.github.near.sweats.items.ItemIdentity;
import io.github.near.sweats.menu.ProtectedMenu;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Detection and evidence only: no deletion, ban, rollback or confiscation.
 *
 * <p>Every scan compares a stack against its own material limit and checks unique item identities
 * across the player's inventory and any open ordinary container. Nested shulker boxes and bundles
 * are inspected recursively. Suspected findings are situations a human should review; confirmed
 * findings are states vanilla cannot produce. Reports are rate-limited per player and code,
 * written to {@code dupe-evidence.log} asynchronously and mirrored to the security channel.
 */
public final class DupeDetector implements Listener, AutoCloseable {

    private static final int MAX_DEPTH = 4;
    private static final int WRITE_QUEUE_CAPACITY = 1_024;

    private final JavaPlugin plugin;
    private final DiscordLogService discord;
    private final Consumer<String> warn;
    private final Path evidenceFile;
    private final ExecutorService writer;
    private final Map<String, Long> lastReported = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile boolean inGameAlerts = true;
    private volatile long reportCooldownMillis = 30_000L;

    public DupeDetector(
            final JavaPlugin plugin, final DiscordLogService discord, final Consumer<String> warn) {
        this.plugin = plugin;
        this.discord = discord;
        this.warn = warn;
        this.evidenceFile = plugin.getDataFolder().toPath().resolve("dupe-evidence.log");
        this.writer = writer();
        reload();
    }

    public void reload() {
        final FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("dupe.enabled", true);
        inGameAlerts = config.getBoolean("dupe.alerts.in-game", true);
        reportCooldownMillis = Math.max(
                0L, config.getLong("dupe.report-cooldown-seconds", 30L) * 1_000L);
    }

    // ------------------------------------------------------------------ capture points

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        schedule(event.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(final InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof final Player player) {
            schedule(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(final InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof final Player player) {
            schedule(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof final Player player) {
            schedule(player, null);
        }
    }

    /** Called by the menu guard; the detail is evidence text, not a finding by itself. */
    public void reportSuspicious(final String detail) {
        if (!enabled || detail == null || detail.isBlank()) {
            return;
        }
        if (!shouldReport("menu:" + detail, -1L)) {
            return;
        }
        final String line = Instant.now() + " SUSPECTED menu-guard " + oneLine(detail);
        write(line);
        discord.logDupe("SUSPECTED", "menu-guard", detail);
    }

    // ------------------------------------------------------------------ scanning

    private void schedule(final Player player, final Inventory extra) {
        if (!enabled || plugin == null) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    scan(player, extra);
                }
            });
        } catch (final RuntimeException unavailable) {
            // Scheduling is only unavailable in serverless tests; no scan is needed there.
        }
    }

    private void scan(final Player player, final Inventory extra) {
        final Map<String, String> identities = new LinkedHashMap<>();
        final Map<String, DupeRules.Finding> findings = new LinkedHashMap<>();
        scanContents(player.getInventory().getContents(), "inventory",
                identities, findings, 0);
        if (extra != null && !(extra.getHolder() instanceof ProtectedMenu)) {
            scanContents(extra.getContents(), "container", identities, findings, 0);
        }
        for (final DupeRules.Finding finding : findings.values()) {
            report(player, finding);
        }
    }

    private void scanContents(
            final ItemStack[] contents,
            final String location,
            final Map<String, String> identities,
            final Map<String, DupeRules.Finding> findings,
            final int depth) {
        if (contents == null || depth > MAX_DEPTH) {
            return;
        }
        for (int slot = 0; slot < contents.length; slot++) {
            final ItemStack item = contents[slot];
            if (item == null || item.isEmpty()) {
                continue;
            }
            final String here = location + "[" + slot + "]";
            final String identity = ItemIdentity.raw(plugin, item).orElse(null);
            final String fingerprint = DupeRules.fingerprint(
                    item.getType().name(), item.getAmount(), identity);

            DupeRules.stack(item.getAmount(), item.getType().getMaxStackSize(), identity != null)
                    .ifPresent(finding -> findings.putIfAbsent(
                            finding.code(),
                            new DupeRules.Finding(
                                    finding.classification(),
                                    finding.code(),
                                    finding.detail() + " at " + here + " (" + fingerprint + ")")));

            if (identity != null) {
                final String previous = identities.putIfAbsent(identity, here);
                if (previous != null) {
                    DupeRules.duplicateIdentity(identity, previous, here)
                            .ifPresent(finding -> findings.putIfAbsent(
                                    finding.code(),
                                    new DupeRules.Finding(
                                            finding.classification(),
                                            finding.code(),
                                            finding.detail() + " (" + fingerprint + ")")));
                }
                if (depth > 1) {
                    DupeRules.uniqueInContainer(identity, here)
                            .ifPresent(finding -> findings.putIfAbsent(
                                    finding.code(), finding));
                }
            }

            final ItemMeta meta = item.getItemMeta();
            if (meta instanceof final BlockStateMeta blockState
                    && blockState.getBlockState() instanceof final ShulkerBox shulker) {
                final ItemStack[] nested = shulker.getInventory().getContents();
                if (containsContainer(nested)) {
                    findings.putIfAbsent(
                            "nested-container",
                            new DupeRules.Finding(
                                    DupeRules.Classification.SUSPECTED,
                                    "nested-container",
                                    "container inside " + here));
                }
                scanContents(nested, here + ".shulker", identities, findings, depth + 1);
            } else if (meta instanceof final BundleMeta bundle && bundle.hasItems()) {
                final ItemStack[] nested = bundle.getItems().toArray(ItemStack[]::new);
                if (containsContainer(nested)) {
                    findings.putIfAbsent(
                            "nested-container",
                            new DupeRules.Finding(
                                    DupeRules.Classification.SUSPECTED,
                                    "nested-container",
                                    "container inside " + here));
                }
                scanContents(nested, here + ".bundle", identities, findings, depth + 1);
            }
        }
    }

    private boolean containsContainer(final ItemStack[] items) {
        if (items == null) {
            return false;
        }
        for (final ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            final ItemMeta meta = item.getItemMeta();
            if (meta instanceof BundleMeta) {
                return true;
            }
            if (meta instanceof final BlockStateMeta blockState
                    && blockState.getBlockState() instanceof ShulkerBox) {
                return true;
            }
        }
        return false;
    }

    private void report(final Player player, final DupeRules.Finding finding) {
        final String key = player.getUniqueId() + ":" + finding.code();
        if (!shouldReport(key, player.getUniqueId().getMostSignificantBits())) {
            return;
        }
        final String detail = "UUID: `" + player.getUniqueId() + "`\nCode: `"
                + finding.code() + "`\n" + finding.detail();
        write(Instant.now() + " " + finding.classification() + " player=" + player.getName()
                + " uuid=" + player.getUniqueId() + " code=" + finding.code()
                + " " + oneLine(finding.detail()));
        discord.logDupe(finding.classification().name(), player.getName(), detail);
        if (inGameAlerts) {
            alertStaff(player, finding);
        }
    }

    private boolean shouldReport(final String key, final long salt) {
        if (reportCooldownMillis <= 0L) {
            return true;
        }
        final long now = System.currentTimeMillis();
        final Long previous = lastReported.get(key);
        if (previous != null && now - previous < reportCooldownMillis) {
            return false;
        }
        lastReported.put(key, now);
        if (lastReported.size() > 10_000) {
            final long cutoff = now - reportCooldownMillis;
            lastReported.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
        return true;
    }

    private void alertStaff(final Player suspect, final DupeRules.Finding finding) {
        try {
            for (final Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("sweats.dupe.alerts")) {
                    online.sendMessage(Component.text("[Dupe] ", NamedTextColor.RED)
                            .append(Component.text(finding.classification() + " ", NamedTextColor.GOLD))
                            .append(Component.text(suspect.getName() + ": " + finding.code(),
                                    NamedTextColor.GRAY)));
                }
            }
        } catch (final RuntimeException unavailable) {
            // No live server; the evidence file and Discord record are still written.
        }
    }

    private void write(final String line) {
        try {
            writer.execute(() -> {
                try {
                    final Path parent = evidenceFile.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(evidenceFile, line + System.lineSeparator(),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (final IOException failure) {
                    warn.accept("Could not append dupe evidence: " + failure.getMessage());
                }
            });
        } catch (final RejectedExecutionException full) {
            warn.accept("Dupe evidence queue is full; a record was lost");
        }
    }

    private static String oneLine(final String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3L, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        lastReported.clear();
    }

    private static ThreadPoolExecutor writer() {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY),
                runnable -> {
                    final Thread thread = new Thread(
                            runnable, "Sweats-Dupe-Evidence-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
