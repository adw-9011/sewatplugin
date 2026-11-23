package io.github.near.sweats.commands;

import io.github.near.sweats.scare.ScareCatalog;
import io.github.near.sweats.scare.ScarePreset;
import io.github.near.sweats.scare.ScareService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /scare <player> [jumpscare]} - admin jumpscares from {@link ScareCatalog}. */
public final class ScareCommand implements CommandExecutor, TabCompleter {

    private final ScareService scareService;
    private final PermissionService permissions;

    public ScareCommand(final ScareService scareService, final PermissionService permissions) {
        this.scareService = scareService;
        this.permissions = permissions;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.SCARE)) {
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(usage(label));
            return true;
        }

        final Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("No online player named '" + args[0] + "'.", NamedTextColor.RED));
            return true;
        }

        final boolean bypass = sender.hasPermission("sweats.scare.bypass");
        final String requested = args.length > 1 ? args[1] : ScareCatalog.DEFAULT_ID;

        final Optional<ScarePreset> preset = ScareCatalog.byId(requested);
        if (preset.isEmpty()) {
            sender.sendMessage(Component.text()
                    .append(Component.text("Unknown jumpscare '" + requested + "'.", NamedTextColor.RED))
                    .append(Component.newline())
                    .append(Component.text("Use one of: " + String.join(", ", ScareCatalog.ids()), NamedTextColor.GRAY))
                    .build());
            return true;
        }

        return run(sender, target, preset.get(), bypass);
    }

    private boolean run(
            final CommandSender sender, final Player target, final ScarePreset preset, final boolean bypass) {
        final long remaining = scareService.scare(target, preset, bypass);
        if (remaining > 0L) {
            sender.sendMessage(cooldown(target, remaining));
            return true;
        }

        sender.sendMessage(Component.text()
                .append(Component.text("Scared ", NamedTextColor.GRAY))
                .append(Component.text(target.getName(), NamedTextColor.WHITE))
                .append(Component.text(" with ", NamedTextColor.GRAY))
                .append(Component.text(preset.id(), NamedTextColor.RED))
                .append(Component.text(" (" + preset.displayName() + ")", NamedTextColor.DARK_GRAY))
                .build());
        return true;
    }

    private static Component cooldown(final Player target, final long remaining) {
        return Component.text(
                target.getName() + " is still shaking - wait "
                        + String.format(Locale.ROOT, "%.1fs", remaining / 1000.0D) + ".",
                NamedTextColor.RED);
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        if (!permissions.has(sender, PermissionNodes.SCARE)) {
            return List.of();
        }

        final String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);

        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }

        if (args.length == 2) {
            return ScareCatalog.ids().stream().filter(id -> id.startsWith(prefix)).toList();
        }

        return List.of();
    }

    private static Component usage(final String label) {
        return Component.text()
                .append(Component.text("Usage: /" + label + " <player> [" + String.join("|", ScareCatalog.ids()) + "]",
                        NamedTextColor.RED))
                .append(Component.newline())
                .append(Component.text("Example: /" + label + " Notch jumpscare2", NamedTextColor.GRAY))
                .build();
    }
}