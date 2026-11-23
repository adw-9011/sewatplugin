package io.github.near.sweats.commands;

import io.github.near.sweats.auth.AuthService;
import io.github.near.sweats.teams.TeamService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Declines a pending team invitation. */
public final class TeamDenyCommand implements CommandExecutor {

    private final TeamService teams;
    private final AuthService auth;

    public TeamDenyCommand(final TeamService teams, final AuthService auth) {
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
            final String team = teams.deny(
                    player.getUniqueId(), args.length == 1 ? args[0] : null);
            sender.sendMessage(CommandSupport.success(
                    "You declined the invitation to " + team + "."));
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(CommandSupport.error(exception.getMessage()));
        }
        return true;
    }
}
