package io.github.near.sweats.commands;

import io.github.near.sweats.SweatsPlugin;
import io.github.near.sweats.display.DisplaySettings;
import io.github.near.sweats.display.DisplayService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Scoreboard controls exposed under {@code /sweats scoreboard} to avoid Paper's /scoreboard. */
public final class ScoreboardCommand {

    public static final String TOGGLE_PERMISSION = "sweats.scoreboard.toggle";
    public static final String ADMIN_PERMISSION = "sweats.scoreboard.admin";
    private static final List<String> ACTIONS = List.of(
            "toggle", "reload", "edit", "settitle", "setline", "reset");

    private final SweatsPlugin plugin;

    public ScoreboardCommand(final SweatsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (args.length == 0) {
            showStatus(sender, label);
            return true;
        }

        final String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("toggle")) {
            return toggle(sender, label, args);
        }
        if (!requireAdmin(sender)) {
            return true;
        }
        return switch (action) {
            case "reload" -> reload(sender, label, args);
            case "edit" -> edit(sender, label, args);
            case "settitle" -> setTitle(sender, label, args);
            case "setline" -> setLine(sender, label, args);
            case "reset" -> reset(sender, label, args);
            default -> {
                sender.sendMessage(error("Usage: /" + label + " scoreboard <" + String.join("|", ACTIONS) + ">"));
                yield true;
            }
        };
    }

    public List<String> complete(final CommandSender sender, final String[] args) {
        if (args.length == 1) {
            final List<String> visible = sender.hasPermission(ADMIN_PERMISSION)
                    ? ACTIONS : List.of("toggle");
            return CommandSupport.matching(visible, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setline")
                && sender.hasPermission(ADMIN_PERMISSION)) {
            return CommandSupport.matching(
                    java.util.stream.IntStream.rangeClosed(1, DisplaySettings.MAX_LINES)
                            .mapToObj(Integer::toString).toList(), args[1]);
        }
        return List.of();
    }

    private void showStatus(final CommandSender sender, final String label) {
        final DisplayService display = plugin.displayService();
        if (display == null) {
            sender.sendMessage(error("The scoreboard is not available."));
            return;
        }
        if (sender instanceof final Player player) {
            final boolean visible = display.scoreboardVisible(player.getUniqueId());
            sender.sendMessage(Component.text(
                    "Your scoreboard is " + (visible ? "visible" : "hidden") + ". Use /" + label
                            + " scoreboard toggle to change it.", NamedTextColor.GRAY));
        } else {
            final DisplaySettings settings = display.settings();
            sender.sendMessage(Component.text(
                    "Scoreboard is " + (settings.scoreboardEnabled() ? "enabled" : "disabled")
                            + "; default visibility is " + (settings.defaultVisible() ? "on" : "off") + ".",
                    NamedTextColor.GRAY));
        }
    }

    private boolean toggle(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 1) {
            sender.sendMessage(error("Usage: /" + label + " scoreboard toggle"));
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(error("Only players can toggle their own scoreboard."));
            return true;
        }
        if (!sender.hasPermission(TOGGLE_PERMISSION)) {
            sender.sendMessage(error("You do not have permission to toggle your scoreboard."));
            return true;
        }
        final DisplayService display = plugin.displayService();
        if (display == null) {
            sender.sendMessage(error("The scoreboard is not available."));
            return true;
        }
        final boolean visible = display.toggleScoreboard(player);
        if (visible) {
            sender.sendMessage(success("Scoreboard enabled."));
        } else if (!display.settings().scoreboardEnabled()) {
            sender.sendMessage(error("The scoreboard is disabled by server configuration."));
        } else {
            sender.sendMessage(success("Scoreboard hidden."));
        }
        return true;
    }

    private boolean reload(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 1) {
            sender.sendMessage(error("Usage: /" + label + " scoreboard reload"));
            return true;
        }
        plugin.reloadConfig();
        plugin.displayService().reloadSettings();
        sender.sendMessage(success("Scoreboard configuration reloaded."));
        return true;
    }

    private boolean edit(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 1) {
            sender.sendMessage(error("Usage: /" + label + " scoreboard edit"));
            return true;
        }
        sender.sendMessage(Component.text(
                "Scoreboard customization: config.yml (scoreboard.title, scoreboard.lines, "
                        + "scoreboard.server-address). Use /" + label
                        + " scoreboard settitle <text>, setline <line> <text>, or reset.",
                NamedTextColor.GRAY));
        return true;
    }

    private boolean setTitle(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(error("Usage: /" + label + " scoreboard settitle <text>"));
            return true;
        }
        final String title = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        if (title.isBlank() || title.length() > 128) {
            sender.sendMessage(error("Title must contain 1 to 128 characters."));
            return true;
        }
        plugin.getConfig().set("scoreboard.title", title);
        if (saveAndApply(sender)) {
            sender.sendMessage(success("Scoreboard title updated and saved."));
        }
        return true;
    }

    private boolean setLine(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(error("Usage: /" + label + " scoreboard setline <1-15> <text>"));
            return true;
        }
        final int lineNumber;
        try {
            lineNumber = Integer.parseInt(args[1]);
        } catch (final NumberFormatException exception) {
            sender.sendMessage(error("Line must be a number from 1 to " + DisplaySettings.MAX_LINES + "."));
            return true;
        }
        if (lineNumber < 1 || lineNumber > DisplaySettings.MAX_LINES) {
            sender.sendMessage(error("Line must be a number from 1 to " + DisplaySettings.MAX_LINES + "."));
            return true;
        }
        final String line = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        if (line.length() > 128) {
            sender.sendMessage(error("A scoreboard line cannot exceed 128 characters."));
            return true;
        }
        final List<String> lines = new ArrayList<>(DisplaySettings.from(plugin.getConfig()).lines());
        while (lines.size() < lineNumber) {
            lines.add(" ");
        }
        lines.set(lineNumber - 1, line);
        plugin.getConfig().set("scoreboard.lines", lines);
        if (saveAndApply(sender)) {
            sender.sendMessage(success("Scoreboard line " + lineNumber + " updated and saved."));
        }
        return true;
    }

    private boolean reset(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 1) {
            sender.sendMessage(error("Usage: /" + label + " scoreboard reset"));
            return true;
        }
        plugin.getConfig().set("scoreboard.enabled", true);
        plugin.getConfig().set("scoreboard.default-visible", true);
        plugin.getConfig().set("scoreboard.title", DisplaySettings.DEFAULT_TITLE);
        plugin.getConfig().set("scoreboard.server-address", "");
        plugin.getConfig().set("scoreboard.refresh-interval", 20);
        plugin.getConfig().set("scoreboard.lines", DisplaySettings.DEFAULT_LINES);
        if (saveAndApply(sender)) {
            sender.sendMessage(success("Default scoreboard settings restored and saved."));
        }
        return true;
    }

    private boolean saveAndApply(final CommandSender sender) {
        final Path configFile = plugin.getDataFolder().toPath().resolve("config.yml");
        try {
            plugin.getConfig().save(configFile.toFile());
        } catch (final IOException exception) {
            plugin.reloadConfig();
            plugin.getLogger().warning("Could not save scoreboard config: " + exception.getMessage());
            sender.sendMessage(error("Could not save scoreboard configuration; check the server log."));
            return false;
        }
        plugin.displayService().reloadSettings();
        return true;
    }

    private static boolean requireAdmin(final CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        sender.sendMessage(error("You do not have permission to customize the scoreboard."));
        return false;
    }

    private static Component error(final String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    private static Component success(final String text) {
        return Component.text(text, NamedTextColor.GREEN);
    }
}
