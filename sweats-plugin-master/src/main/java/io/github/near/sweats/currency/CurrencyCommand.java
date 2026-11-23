package io.github.near.sweats.currency;

import io.github.near.sweats.commands.CommandSupport;
import io.github.near.sweats.commands.PlayerLookup;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.Rank;
import io.github.near.sweats.rank.RankService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /currency give <player> <sweatcoins|tryhardshards> <amount> [reason]}.
 *
 * <p>Authorization is strictly limited to console, DEV rank, and CM rank.
 * The generic {@code sweats.currency.give} permission node is NOT used for authorization
 * because the permission system has an Owner bypass. OP status alone does not grant access.
 * Paid ranks do not grant access.
 */
public final class CurrencyCommand implements CommandExecutor, TabCompleter {

    private final CurrencyService currency;
    private final RankService rankService;

    public CurrencyCommand(final CurrencyService currency, final RankService rankService) {
        this.currency = currency;
        this.rankService = rankService;
    }

    /**
     * Checks if the sender is authorized to grant currency.
     * Only console, DEV rank, and CM rank are allowed.
     * OP status, Owner UUID, and paid ranks are explicitly denied.
     */
    static boolean authorized(final RankService rankService, final CommandSender sender) {
        // Console always authorized
        if (!(sender instanceof final Player player)) {
            return true;
        }
        // Check for DEV or CM rank by key
        final Rank rank = rankService.rankOf(player.getUniqueId());
        final String key = rank.key();
        return "dev".equals(key) || "cm".equals(key);
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!authorized(rankService, sender)) {
            sender.sendMessage(error("You do not have permission to give currency."));
            return true;
        }
        if (args.length < 4 || !"give".equalsIgnoreCase(args[0])) {
            sender.sendMessage(error(
                    "Usage: /" + label + " give <player> <sweatcoins|tryhardshards> <amount> [reason]"));
            return true;
        }

        final Optional<PlayerLookup.Target> found = PlayerLookup.find(args[1], sender);
        if (found.isEmpty()) {
            sender.sendMessage(error("Unknown player: " + args[1]));
            return true;
        }
        final PlayerLookup.Target target = found.get();

        final Optional<CurrencyType> type = CurrencyType.parse(args[2]);
        if (type.isEmpty()) {
            sender.sendMessage(error("Unknown currency. Use sweatcoins or tryhardshards."));
            return true;
        }

        final long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (final NumberFormatException invalid) {
            sender.sendMessage(error("Amount must be a whole number."));
            return true;
        }
        if (amount <= 0 || amount > currency.maxGiveAmount()) {
            sender.sendMessage(error(
                    "Amount must be between 1 and " + currency.maxGiveAmount() + "."));
            return true;
        }

        final String reason = args.length > 4
                ? CommandSupport.join(args, 4)
                : "unspecified";
        final UUID issuer = sender instanceof final Player player ? player.getUniqueId() : null;
        final String issuerName = sender.getName();

        currency.grant(issuer, target.id(), type.get(), amount, reason, "command", result -> {
            switch (result) {
                case SUCCESS -> {
                    sender.sendMessage(Component.text(
                            "Gave " + amount + " " + type.get().displayName() + " to "
                                    + target.name()
                                    + (reason.isBlank() || "unspecified".equals(reason)
                                            ? "." : " (" + reason + ")."),
                            NamedTextColor.GREEN));
                    final Player online = target.online();
                    if (online != null && online.isOnline()) {
                        online.sendMessage(Component.text(
                                "You received " + amount + " " + type.get().displayName()
                                        + " from " + issuerName
                                        + (reason.isBlank() || "unspecified".equals(reason)
                                                ? "." : " (" + reason + ")."),
                                type.get().color()));
                    }
                }
                case DISABLED -> sender.sendMessage(error("Currency is disabled."));
                case INVALID_AMOUNT -> sender.sendMessage(error(
                        "Amount must be between 1 and " + currency.maxGiveAmount() + "."));
                case REJECTED -> sender.sendMessage(error(
                        "Could not grant currency: the balance limit was reached or the "
                                + "database is unavailable."));
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!authorized(rankService, sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("give"), args[0]);
        }
        if (args.length == 2) {
            final List<String> names = new ArrayList<>();
            try {
                for (final Player player : Bukkit.getOnlinePlayers()) {
                    if (sender instanceof final Player viewer && !viewer.canSee(player)) {
                        continue;
                    }
                    names.add(player.getName());
                }
            } catch (final RuntimeException noServer) {
                return List.of();
            }
            return filter(names, args[1]);
        }
        if (args.length == 3) {
            final List<String> keys = new ArrayList<>();
            for (final CurrencyType type : CurrencyType.values()) {
                keys.add(type.key());
            }
            return filter(keys, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(final List<String> options, final String prefix) {
        final String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        final List<String> result = new ArrayList<>();
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(needle)) {
                result.add(option);
            }
        }
        return result;
    }

    private static Component error(final String message) {
        return Component.text(message, NamedTextColor.RED);
    }
}
