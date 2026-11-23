package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.teams.TeamService;
import io.github.near.sweats.teams.TeamService.Invitation;
import io.github.near.sweats.teams.TeamService.LeaveResult;
import io.github.near.sweats.teams.TeamService.Team;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Routes the individual {@code /team...} commands. */
public final class TeamCommand implements CommandExecutor, TabCompleter {

    private final TeamService teams;
    private final PermissionService permissions;
    private final AuthService auth;

    public TeamCommand(
            final TeamService teams,
            final PermissionService permissions,
            final AuthService auth) {
        this.teams = teams;
        this.permissions = permissions;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        final String name = command.getName().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "teamcreate" -> create(sender, args);
            case "teamdelete" -> delete(sender, args);
            case "teaminvite" -> invite(sender, args);
            case "teamlist" -> list(sender);
            case "teamleave" -> leave(sender);
            case "teamkick" -> kick(sender, args);
            case "teaminfo" -> info(sender, args);
            default -> false;
        };
    }

    private boolean create(final CommandSender sender, final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.TEAM_CREATE)) {
            return true;
        }
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(CommandSupport.error("Usage: /teamcreate <name>"));
            return true;
        }
        try {
            final Team team = teams.create(player.getUniqueId(), args[0]);
            sender.sendMessage(CommandSupport.success(
                    "Created team " + team.name() + ". Capacity: " + teams.capacity(player.getUniqueId()) + "."));
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }

    private boolean delete(final CommandSender sender, final String[] args) {
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        final boolean staff = permissions.has(sender, PermissionNodes.TEAM_DELETE);
        try {
            final String deleted;
            if (args.length == 0) {
                deleted = teams.delete(player.getUniqueId(), staff);
            } else if (args.length == 1 && staff) {
                deleted = teams.deleteByName(args[0], true);
            } else {
                sender.sendMessage(CommandSupport.error(
                        staff ? "Usage: /teamdelete [team]" : "Usage: /teamdelete"));
                return true;
            }
            sender.sendMessage(CommandSupport.success("Deleted team " + deleted + "."));
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }

    private boolean invite(final CommandSender sender, final String[] args) {
        if (!CommandSupport.require(sender, permissions, PermissionNodes.TEAM_INVITE)) {
            return true;
        }
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(CommandSupport.error("Usage: /teaminvite <player>"));
            return true;
        }
        final Optional<PlayerLookup.Target> found = PlayerLookup.find(args[0], sender);
        if (found.isEmpty() || found.get().online() == null) {
            sender.sendMessage(CommandSupport.error("That player is not online."));
            return true;
        }
        final Player target = found.get().online();
        if (!auth.isAuthenticated(target.getUniqueId())) {
            sender.sendMessage(CommandSupport.error("That player is not logged in."));
            return true;
        }
        try {
            final Invitation invitation = teams.invite(player.getUniqueId(), target.getUniqueId());
            target.sendMessage(Component.text()
                    .append(Component.text("You have been invited to join ", NamedTextColor.GRAY))
                    .append(Component.text(invitation.teamName(), NamedTextColor.AQUA))
                    .append(Component.text("  ", NamedTextColor.GRAY))
                    .append(Component.text("[YES]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/teamaccept " + invitation.teamName()))
                            .hoverEvent(HoverEvent.showText(Component.text("Accept the invitation."))))
                    .append(Component.text(" ", NamedTextColor.GRAY))
                    .append(Component.text("[NO]", NamedTextColor.RED)
                            .clickEvent(ClickEvent.runCommand("/teamdeny " + invitation.teamName()))
                            .hoverEvent(HoverEvent.showText(Component.text("Decline the invitation."))))
                    .build());
            sender.sendMessage(CommandSupport.success(
                    "Invited " + target.getName() + " to " + invitation.teamName() + "."));
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }

    private boolean list(final CommandSender sender) {
        if (sender instanceof final Player player && !auth.isAuthenticated(player.getUniqueId())) {
            sender.sendMessage(CommandSupport.error("You must log in first."));
            return true;
        }
        final var all = teams.all();
        if (all.isEmpty()) {
            sender.sendMessage(Component.text("There are no teams.", NamedTextColor.GRAY));
            return true;
        }
        sender.sendMessage(Component.text("Teams (" + all.size() + "):", NamedTextColor.AQUA));
        for (final Team team : all) {
            sender.sendMessage(Component.text()
                    .append(Component.text(team.name(), NamedTextColor.WHITE))
                    .append(Component.text(" - owner ", NamedTextColor.GRAY))
                    .append(Component.text(nameOf(team.owner()), NamedTextColor.AQUA))
                    .append(Component.text(
                            " (" + team.members().size() + "/" + teams.capacity(team.owner()) + ")",
                            NamedTextColor.DARK_GRAY))
                    .build());
        }
        return true;
    }

    private boolean leave(final CommandSender sender) {
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        try {
            final LeaveResult result = teams.leave(player.getUniqueId());
            if (result.deleted()) {
                sender.sendMessage(CommandSupport.success(
                        "You left team " + result.teamName() + "; it was deleted because it was empty."));
            } else if (result.newOwner() != null) {
                sender.sendMessage(CommandSupport.success(
                        "You left team " + result.teamName() + ". "
                                + nameOf(result.newOwner()) + " is the new owner."));
            } else {
                sender.sendMessage(CommandSupport.success("You left team " + result.teamName() + "."));
            }
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }

    private boolean kick(final CommandSender sender, final String[] args) {
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(CommandSupport.error("Usage: /teamkick <player>"));
            return true;
        }
        final Optional<PlayerLookup.Target> found = PlayerLookup.find(args[0], sender);
        if (found.isEmpty()) {
            sender.sendMessage(CommandSupport.error("Unknown player: " + args[0]));
            return true;
        }
        try {
            teams.kick(
                    player.getUniqueId(),
                    found.get().id(),
                    permissions.has(sender, PermissionNodes.TEAM_MANAGE));
            sender.sendMessage(CommandSupport.success("Kicked " + found.get().name() + " from the team."));
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }

    private boolean info(final CommandSender sender, final String[] args) {
        if (sender instanceof final Player player && !auth.isAuthenticated(player.getUniqueId())) {
            sender.sendMessage(CommandSupport.error("You must log in first."));
            return true;
        }
        final Optional<Team> found;
        if (args.length > 0) {
            found = teams.teamByName(CommandSupport.join(args, 0));
        } else if (sender instanceof final Player player) {
            found = teams.teamOf(player.getUniqueId());
        } else {
            sender.sendMessage(CommandSupport.error("Usage: /teaminfo <team>"));
            return true;
        }
        if (found.isEmpty()) {
            sender.sendMessage(CommandSupport.error("You are not in a team."));
            return true;
        }

        final Team team = found.get();
        sender.sendMessage(Component.text("Team " + team.name(), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Owner: ", NamedTextColor.GRAY)
                .append(Component.text(nameOf(team.owner()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text(
                "Members: " + team.members().size() + "/" + teams.capacity(team.owner()),
                NamedTextColor.GRAY));
        for (final var member : team.members()) {
            sender.sendMessage(Component.text(" - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(nameOf(member), NamedTextColor.GRAY))
                    .append(Component.text(
                            member.equals(team.owner()) ? " (owner)" : "", NamedTextColor.DARK_GRAY)));
        }
        return true;
    }

    private static String nameOf(final java.util.UUID id) {
        final Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        final OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        final String name = offline.getName();
        return name == null || name.isBlank() ? id.toString().substring(0, 8) : name;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args) {
        final String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length != 1) {
            return List.of();
        }
        return switch (name) {
            case "teaminvite", "teamkick" -> CommandSupport.onlineNames(args[0], sender);
            case "teaminfo" -> {
                final List<String> names = new ArrayList<>();
                for (final Team team : teams.all()) {
                    names.add(team.name());
                }
                yield CommandSupport.matching(names, args[0]);
            }
            case "teamdelete" -> {
                if (!permissions.has(sender, PermissionNodes.TEAM_DELETE)) {
                    yield List.of();
                }
                final List<String> names = new ArrayList<>();
                for (final Team team : teams.all()) {
                    names.add(team.name());
                }
                yield CommandSupport.matching(names, args[0]);
            }
            default -> List.of();
        };
    }
}
