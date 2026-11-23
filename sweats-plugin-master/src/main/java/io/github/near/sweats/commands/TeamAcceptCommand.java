package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.teams.TeamService;
import io.github.near.sweats.teams.TeamService.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Accepts a one-time invitation. */
public final class TeamAcceptCommand implements CommandExecutor {

    private final TeamService teams;
    private final AuthService auth;

    public TeamAcceptCommand(final TeamService teams, final AuthService auth) {
        this.teams = teams;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        final Player player = CommandSupport.authenticatedPlayer(sender, auth);
        if (player == null) {
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage(CommandSupport.error("Usage: /" + label + " [team]"));
            return true;
        }
        try {
            final Team team = teams.accept(
                    player.getUniqueId(), args.length == 1 ? args[0] : null);
            sender.sendMessage(CommandSupport.success("You joined team " + team.name() + "."));
            final Component notice = Component.text(
                    player.getName() + " joined the team.", NamedTextColor.GRAY);
            for (final var member : team.members()) {
                if (member.equals(player.getUniqueId())) {
                    continue;
                }
                final Player online = Bukkit.getPlayer(member);
                if (online != null && auth.isAuthenticated(member)) {
                    online.sendMessage(notice);
                }
            }
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }
}
