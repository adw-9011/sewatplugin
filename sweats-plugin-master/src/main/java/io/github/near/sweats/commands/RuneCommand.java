package io.github.near.sweats.commands;

import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.runes.Rune;
import io.github.near.sweats.runes.RuneAccessStore;
import io.github.near.sweats.runes.RuneCatalog;
import io.github.near.sweats.runes.RuneDrops;
import io.github.near.sweats.runes.RuneGui;
import io.github.near.sweats.runes.RuneItems;
import io.github.near.sweats.runes.RuneTable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Commands for the rune browser, Runic Table, drops and access management. */
public final class RuneCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final RuneItems items;
    private final RuneGui gui;
    private final RuneTable table;
    private final RuneAccessStore access;
    private final RuneDrops drops;
    private final PermissionService permissions;

    public RuneCommand(
            final JavaPlugin plugin,
            final RuneItems items,
            final RuneGui gui,
            final RuneTable table,
            final RuneAccessStore access,
            final RuneDrops drops,
            final PermissionService permissions) {
        this.plugin = plugin;
        this.items = items;
        this.gui = gui;
        this.table = table;
        this.access = access;
        this.drops = drops;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "runes" -> runes(sender, label, args);
            case "runetable" -> runeTable(sender, label, args);
            case "adminsword" -> adminSword(sender);
            case "runeluck" -> runeLuck(sender, args);
            case "runeaccess" -> runeAccess(sender, args);
            case "runepermdropchance" -> permanentDropChance(sender, args);
            default -> false;
        };
    }

    // ------------------------------------------------------------------ /runes

    private boolean runes(final CommandSender sender, final String label, final String[] args) {
        if (!CommandSupport.requireAny(
                sender, permissions, PermissionNodes.RUNES, PermissionNodes.RUNES_ADMIN)) {
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!CommandSupport.require(sender, permissions, PermissionNodes.RUNES_ADMIN)) {
                return true;
            }
            giveRune(sender, args);
            return true;
        }
        if (args.length > 0) {
            sender.sendMessage(CommandSupport.error(
                    "Usage: /" + label + " [give <rune> [tier] [player]]"));
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(CommandSupport.error(
                    "Only players can open the rune browser. Use /" + label
                            + " give <rune> [tier] [player]."));
            return true;
        }
        gui.openBrowser(player, 0);
        return true;
    }

    private void giveRune(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(CommandSupport.error(
                    "Usage: /runes give <rune> [tier] [player]"));
            return;
        }
        final Rune rune = RuneCatalog.get(args[1]);
        if (rune == null) {
            sender.sendMessage(CommandSupport.error("Unknown rune: " + args[1]));
            return;
        }
        final int tier = args.length >= 3 ? parseTier(sender, args[2]) : 1;
        if (tier < 1) {
            return;
        }
        final Player target = args.length >= 4
                ? Bukkit.getPlayerExact(args[3])
                : (sender instanceof final Player player ? player : null);
        if (target == null) {
            sender.sendMessage(CommandSupport.error(
                    "Player not found. Use /runes give <rune> [tier] <player>."));
            return;
        }
        give(target, items.runeItem(rune, tier));
        sender.sendMessage(CommandSupport.success(
                "Gave " + rune.name() + " Rune " + RuneItems.roman(tier) + " to "
                        + target.getName() + "."));
    }

    private static int parseTier(final CommandSender sender, final String raw) {
        try {
            final int tier = Integer.parseInt(raw);
            if (tier >= 1 && tier <= RuneItems.MAX_TIER) {
                return tier;
            }
        } catch (final NumberFormatException ignored) {
            // Fall through to the shared error below.
        }
        sender.sendMessage(CommandSupport.error("Tier must be 1-3."));
        return -1;
    }

    // ------------------------------------------------------------------ /runetable

    private boolean runeTable(final CommandSender sender, final String label, final String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!CommandSupport.require(sender, permissions, PermissionNodes.RUNES_ADMIN)) {
                return true;
            }
            final Player target = args.length >= 2
                    ? Bukkit.getPlayerExact(args[1])
                    : (sender instanceof final Player player ? player : null);
            if (target == null) {
                sender.sendMessage(CommandSupport.error(
                        "Usage: /" + label + " give <player> (player must be online)"));
                return true;
            }
            give(target, table.item());
            sender.sendMessage(CommandSupport.success("Gave a Rune Table to " + target.getName() + "."));
            return true;
        }

        if (!CommandSupport.requireAny(
                sender, permissions, PermissionNodes.RUNETABLE, PermissionNodes.RUNES_ADMIN)) {
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(CommandSupport.error("Only players can open the Runic Table."));
            return true;
        }
        if (!table.canUse(player)) {
            sender.sendMessage(CommandSupport.error("You don't have access to the Rune Table."));
            return true;
        }
        gui.openTable(player);
        return true;
    }

    // ------------------------------------------------------------------ /adminsword

    private boolean adminSword(final CommandSender sender) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.RUNES_ADMIN)) {
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(CommandSupport.error("Only players can receive the Admin Sword."));
            return true;
        }
        give(player, items.adminSword(player.getUniqueId()));
        sender.sendMessage(CommandSupport.success("Given the Admin Sword."));
        return true;
    }

    // ------------------------------------------------------------------ /runeluck

    private boolean runeLuck(final CommandSender sender, final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.RUNES_ADMIN)) {
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(CommandSupport.error("Only players can change rune luck."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text(
                    "Your rune luck: x" + drops.luck(player.getUniqueId())
                            + "  (use /runeluck <multiplier|reset>)",
                    NamedTextColor.YELLOW));
            return true;
        }
        if (args[0].equalsIgnoreCase("reset")) {
            drops.setLuck(player.getUniqueId(), 1.0);
            sender.sendMessage(CommandSupport.success("Rune luck reset to x1."));
            return true;
        }
        try {
            final double multiplier = Double.parseDouble(args[0]);
            if (multiplier < 1) {
                throw new NumberFormatException();
            }
            drops.setLuck(player.getUniqueId(), multiplier);
            sender.sendMessage(CommandSupport.success(
                    "Rune luck set to x" + multiplier + " (resets on restart)."));
        } catch (final NumberFormatException exception) {
            sender.sendMessage(CommandSupport.error("Use a number >= 1, or 'reset'."));
        }
        return true;
    }

    // ------------------------------------------------------------------ /runeaccess

    private boolean runeAccess(final CommandSender sender, final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.RUNES_ADMIN)) {
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            final List<String> names = new ArrayList<>();
            for (final UUID id : access.granted()) {
                final String name = Bukkit.getOfflinePlayer(id).getName();
                names.add(name != null ? name : id.toString());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            sender.sendMessage(Component.text(
                    names.isEmpty()
                            ? "Nobody has Rune Table access."
                            : "Rune Table access: " + String.join(", ", names),
                    NamedTextColor.YELLOW));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(CommandSupport.error(
                    "Usage: /runeaccess <grant|revoke|check> <player>  or  /runeaccess list"));
            return true;
        }
        final Optional<PlayerLookup.Target> target = PlayerLookup.find(args[1], sender);
        if (target.isEmpty()) {
            sender.sendMessage(CommandSupport.error(
                    "Player not found (they must be online or have joined before)."));
            return true;
        }
        final String name = target.get().name();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "grant" -> {
                if (!access.setAccess(target.get().id(), true)) {
                    sender.sendMessage(CommandSupport.error(
                            "Could not save Rune Table access; nothing was changed."));
                    return true;
                }
                sender.sendMessage(CommandSupport.success(
                        name + " can now craft and use the Rune Table."));
            }
            case "revoke" -> {
                if (!access.setAccess(target.get().id(), false)) {
                    sender.sendMessage(CommandSupport.error(
                            "Could not save Rune Table access; nothing was changed."));
                    return true;
                }
                sender.sendMessage(CommandSupport.success(
                        name + " no longer has Rune Table access."));
            }
            case "check" -> sender.sendMessage(Component.text(
                    name + (access.has(target.get().id()) ? " HAS" : " does NOT have")
                            + " Rune Table access.",
                    NamedTextColor.YELLOW));
            default -> sender.sendMessage(CommandSupport.error(
                    "Usage: /runeaccess <grant|revoke|check> <player>"));
        }
        return true;
    }

    // ------------------------------------------------------------------ /runepermdropchance

    private boolean permanentDropChance(final CommandSender sender, final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.RUNES_ADMIN)) {
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(CommandSupport.error(
                    "Usage: /runepermdropchance <player> <amount>  |  <player> add|remove|set <amount>  |  <player> reset|view"));
            return true;
        }
        final Optional<PlayerLookup.Target> target = PlayerLookup.find(args[0], sender);
        if (target.isEmpty()) {
            sender.sendMessage(CommandSupport.error(
                    "Player not found (they must be online or have joined before)."));
            return true;
        }
        final UUID id = target.get().id();
        final String name = target.get().name();
        final double current = access.bonus(id);
        if (args.length == 1 || args[1].equalsIgnoreCase("view")) {
            sender.sendMessage(Component.text(
                    name + " has a permanent drop bonus of +" + format(current) + "%.",
                    NamedTextColor.YELLOW));
            return true;
        }

        String operation = args[1].toLowerCase(Locale.ROOT);
        final double next;
        if (operation.equals("reset")) {
            next = 0;
        } else {
            final String raw;
            if (operation.equals("set")
                    || operation.equals("add")
                    || operation.equals("remove")) {
                if (args.length < 3) {
                    sender.sendMessage(CommandSupport.error(
                            "Give an amount, e.g. /runepermdropchance " + name + " " + operation
                                    + " 0.1"));
                    return true;
                }
                raw = args[2];
            } else {
                operation = "set";
                raw = args[1];
            }
            final double value;
            try {
                value = Double.parseDouble(raw);
            } catch (final NumberFormatException exception) {
                sender.sendMessage(CommandSupport.error("'" + raw + "' is not a number."));
                return true;
            }
            if (value < 0) {
                sender.sendMessage(CommandSupport.error(
                        "Amount can't be negative (use 'remove')."));
                return true;
            }
            next = switch (operation) {
                case "add" -> current + value;
                case "remove" -> Math.max(0, current - value);
                default -> value;
            };
        }

        final double max = plugin.getConfig().getDouble("runes.drops.max-perm-bonus", 5.0);
        final boolean capped = next > max;
        final double stored = capped ? max : next;
        if (!access.setBonus(id, stored)) {
            sender.sendMessage(CommandSupport.error(
                    "Could not save the drop bonus; nothing was changed."));
            return true;
        }
        sender.sendMessage(CommandSupport.success(
                name + " now has a permanent drop bonus of +" + format(stored) + "%"
                        + (capped ? " (capped at runes.drops.max-perm-bonus)" : "") + "."));
        return true;
    }

    private static String format(final double value) {
        return new BigDecimal(value)
                .setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    // ------------------------------------------------------------------ helpers

    private static void give(final Player player, final ItemStack item) {
        player.getInventory()
                .addItem(item)
                .values()
                .forEach(dropped -> player.getWorld()
                        .dropItemNaturally(player.getLocation(), dropped));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        final String name = command.getName().toLowerCase(Locale.ROOT);
        if ("runes".equals(name)) {
            if (!permissions.has(sender, PermissionNodes.RUNES)
                    && !permissions.has(sender, PermissionNodes.RUNES_ADMIN)) {
                return List.of();
            }
            if (args.length <= 1) {
                if (!permissions.has(sender, PermissionNodes.RUNES_ADMIN)) {
                    return List.of();
                }
                return CommandSupport.matching(List.of("give"), args.length == 0 ? "" : args[0]);
            }
            if (args[0].equalsIgnoreCase("give")) {
                if (args.length == 2) {
                    return runeIds(args[1]);
                }
                if (args.length == 3) {
                    return CommandSupport.matching(List.of("1", "2", "3"), args[2]);
                }
                if (args.length == 4) {
                    return CommandSupport.matching(CommandSupport.onlineNames(args[3]), args[3]);
                }
            }
            return List.of();
        }

        if ("runetable".equals(name)) {
            if (args.length <= 1) {
                if (!permissions.has(sender, PermissionNodes.RUNES_ADMIN)) {
                    return List.of();
                }
                return CommandSupport.matching(List.of("give"), args.length == 0 ? "" : args[0]);
            }
            if (args[0].equalsIgnoreCase("give")
                    && permissions.has(sender, PermissionNodes.RUNES_ADMIN)) {
                return args.length == 2
                        ? CommandSupport.matching(CommandSupport.onlineNames(args[1]), args[1])
                        : List.of();
            }
            return List.of();
        }

        if (!permissions.has(sender, PermissionNodes.RUNES_ADMIN)) {
            return List.of();
        }
        return switch (name) {
            case "runeluck" -> args.length <= 1
                    ? CommandSupport.matching(
                            List.of("reset", "1", "2", "5", "10"),
                            args.length == 0 ? "" : args[0])
                    : List.of();
            case "runeaccess" -> {
                if (args.length == 1) {
                    yield CommandSupport.matching(
                            List.of("grant", "revoke", "check", "list"), args[0]);
                }
                yield args.length == 2
                        ? CommandSupport.matching(CommandSupport.onlineNames(args[1]), args[1])
                        : List.of();
            }
            case "runepermdropchance" -> {
                if (args.length == 1) {
                    yield CommandSupport.matching(CommandSupport.onlineNames(args[0]), args[0]);
                }
                if (args.length == 2) {
                    yield CommandSupport.matching(
                            List.of("set", "add", "remove", "reset", "view"), args[1]);
                }
                yield args.length == 3
                        ? CommandSupport.matching(List.of("0.1", "0.5", "1.0"), args[2])
                        : List.of();
            }
            default -> List.of();
        };
    }

    private static List<String> runeIds(final String prefix) {
        final String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return RuneCatalog.all().stream()
                .map(Rune::id)
                .filter(id -> id.startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
