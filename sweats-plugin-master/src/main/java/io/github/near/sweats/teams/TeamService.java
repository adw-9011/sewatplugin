package io.github.near.sweats.teams;

import io.github.near.sweats.owner.Owner;
import io.github.near.sweats.perks.RankPerks;
import io.github.near.sweats.rank.RankService;
import io.github.near.sweats.teams.TeamStore.StoredTeam;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Team ownership, membership, invitations and capacity. Team data is keyed by UUID only and is
 * never removed by a rank change; only new invitations/accepts are blocked when a team is over
 * its current rank limit.
 */
public final class TeamService implements Listener, AutoCloseable {

    public record Team(UUID id, String name, UUID owner, List<UUID> members) {

        public boolean isMember(final UUID id) {
            return members.contains(id);
        }
    }

    public record Invitation(
            UUID teamId,
            String teamName,
            UUID inviter,
            UUID target,
            long expiresAt) {}

    public record LeaveResult(String teamName, UUID newOwner, boolean deleted) {}

    private final TeamStore store;
    private final RankService ranks;
    private final Consumer<String> warn;
    private final LongSupplier clock;
    private final Map<UUID, Invitation> invitations = new LinkedHashMap<>();

    private volatile int baseTeamSize = 4;
    private volatile int inviteSeconds = 60;

    public TeamService(
            final Path folder,
            final RankService ranks,
            final Consumer<String> warn) {
        this(folder, ranks, warn, System::currentTimeMillis);
    }

    TeamService(
            final Path folder,
            final RankService ranks,
            final Consumer<String> warn,
            final LongSupplier clock) {
        this.ranks = Objects.requireNonNull(ranks, "ranks");
        this.warn = Objects.requireNonNull(warn, "warn");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = new TeamStore(folder.resolve("teams.yml"));
        this.store.load(warn);
    }

    public void reloadSettings(final int baseTeamSize, final int inviteSeconds) {
        this.baseTeamSize = Math.max(1, baseTeamSize);
        this.inviteSeconds = Math.max(1, inviteSeconds);
    }

    public Optional<Team> teamOf(final UUID player) {
        return store.byMember(player).map(this::view);
    }

    public Optional<Team> teamByName(final String name) {
        return store.byName(name).map(this::view);
    }

    public Optional<Team> team(final UUID id) {
        return store.team(id).map(this::view);
    }

    public Collection<Team> all() {
        final List<Team> teams = new ArrayList<>();
        for (final StoredTeam team : store.teams()) {
            teams.add(view(team));
        }
        teams.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return teams;
    }

    public int capacity(final UUID owner) {
        final int rankLimit = RankPerks.of(ranks.rankOf(owner)).teamLimit();
        return rankLimit < 0 ? baseTeamSize : rankLimit;
    }

    public Team create(final UUID owner, final String requestedName) {
        final String name = validateName(requestedName);
        if (store.byMember(owner).isPresent()) {
            throw new IllegalArgumentException("You are already in a team.");
        }
        if (store.nameTaken(name)) {
            throw new IllegalArgumentException("A team named " + name + " already exists.");
        }
        final StoredTeam team = new StoredTeam(UUID.randomUUID(), name, owner);
        team.members().add(owner);
        store.put(team);
        if (!store.save(warn)) {
            store.remove(team.id());
            throw new IllegalStateException("Could not save the team; nothing was created.");
        }
        return view(team);
    }

    /** Deletes a team. Only the owner, or staff with the delete permission, may call this. */
    public String delete(final UUID actor, final boolean staff) {
        final StoredTeam team = store.byMember(actor)
                .orElseThrow(() -> new IllegalArgumentException("You are not in a team."));
        if (!staff && !team.owner().equals(actor)) {
            throw new IllegalArgumentException("Only the team owner can delete the team.");
        }
        return deleteStored(team);
    }

    /** Staff deletion by name, used when the staff member is not in the team. */
    public String deleteByName(final String name, final boolean staff) {
        if (!staff) {
            throw new IllegalArgumentException("Only the team owner can delete the team.");
        }
        final StoredTeam team = store.byName(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown team: " + name));
        return deleteStored(team);
    }

    private String deleteStored(final StoredTeam team) {
        final String name = team.name();
        store.remove(team.id());
        if (!store.save(warn)) {
            store.put(team);
            throw new IllegalStateException("Could not save the team; nothing was deleted.");
        }
        invitations.entrySet().removeIf(entry -> entry.getValue().teamId().equals(team.id()));
        return name;
    }

    /** Invites one player. The caller is always the current owner. */
    public Invitation invite(final UUID inviter, final UUID target) {
        final long now = clock.getAsLong();
        purgeExpired(now);
        if (inviter.equals(target)) {
            throw new IllegalArgumentException("You cannot invite yourself.");
        }
        final StoredTeam team = store.byMember(inviter)
                .orElseThrow(() -> new IllegalArgumentException("You are not in a team."));
        if (!team.owner().equals(inviter)) {
            throw new IllegalArgumentException("Only the team owner can invite players.");
        }
        if (store.byMember(target).isPresent()) {
            throw new IllegalArgumentException("That player is already in a team.");
        }
        if (team.members().size() >= capacity(team.owner())) {
            throw new IllegalArgumentException("Your team is full ("
                    + team.members().size() + "/" + capacity(team.owner()) + ").");
        }
        final Invitation existing = invitations.get(target);
        if (existing != null && existing.teamId().equals(team.id())) {
            throw new IllegalArgumentException("That player already has a pending invitation.");
        }
        final Invitation invitation = new Invitation(
                team.id(), team.name(), inviter, target, now + inviteSeconds * 1000L);
        invitations.put(target, invitation);
        return invitation;
    }

    public Optional<Invitation> invitation(final UUID target) {
        final long now = clock.getAsLong();
        purgeExpired(now);
        final Invitation invitation = invitations.get(target);
        return invitation == null ? Optional.empty() : Optional.of(invitation);
    }

    /**
     * Accepts the target's pending invitation. Everything is rechecked here instead of trusting
     * the click payload.
     */
    public Team accept(final UUID target, final String teamName) {
        final long now = clock.getAsLong();
        purgeExpired(now);
        final Invitation invitation = invitations.get(target);
        if (invitation == null) {
            throw new IllegalArgumentException("You do not have a pending team invitation.");
        }
        if (teamName != null && !teamName.isBlank()
                && !invitation.teamName().equalsIgnoreCase(teamName.trim())) {
            throw new IllegalArgumentException("That invitation is not for that team.");
        }
        final StoredTeam team = store.team(invitation.teamId())
                .orElseThrow(() -> {
                    invitations.remove(target);
                    return new IllegalArgumentException("That team no longer exists.");
                });
        if (!team.owner().equals(invitation.inviter())) {
            invitations.remove(target);
            throw new IllegalArgumentException("That invitation is no longer valid.");
        }
        if (store.byMember(target).isPresent()) {
            invitations.remove(target);
            throw new IllegalArgumentException("You are already in a team.");
        }
        if (team.members().size() >= capacity(team.owner())) {
            throw new IllegalArgumentException("That team is full ("
                    + team.members().size() + "/" + capacity(team.owner()) + ").");
        }

        invitations.remove(target);
        store.addMember(team, target);
        if (!store.save(warn)) {
            store.removeMember(team, target);
            invitations.put(target, invitation);
            throw new IllegalStateException("Could not save your team membership.");
        }
        return view(team);
    }

    public String deny(final UUID target, final String teamName) {
        final long now = clock.getAsLong();
        purgeExpired(now);
        final Invitation invitation = invitations.get(target);
        if (invitation == null) {
            throw new IllegalArgumentException("You do not have a pending team invitation.");
        }
        if (teamName != null && !teamName.isBlank()
                && !invitation.teamName().equalsIgnoreCase(teamName.trim())) {
            throw new IllegalArgumentException("That invitation is not for that team.");
        }
        invitations.remove(target);
        return invitation.teamName();
    }

    public LeaveResult leave(final UUID player) {
        final StoredTeam team = store.byMember(player)
                .orElseThrow(() -> new IllegalArgumentException("You are not in a team."));
        final String teamName = team.name();
        if (team.members().size() <= 1) {
            store.remove(team.id());
            if (!store.save(warn)) {
                store.put(team);
                throw new IllegalStateException("Could not save the team change.");
            }
            invitations.entrySet().removeIf(entry -> entry.getValue().teamId().equals(team.id()));
            return new LeaveResult(teamName, null, true);
        }

        final UUID previousOwner = team.owner();
        store.removeMember(team, player);
        UUID newOwner = previousOwner;
        if (!previousOwner.equals(player)) {
            // A member leaving does not change ownership.
        } else {
            newOwner = team.members().iterator().next();
            team.owner(newOwner);
        }
        if (!store.save(warn)) {
            store.addMember(team, player);
            team.owner(previousOwner);
            throw new IllegalStateException("Could not save the team change.");
        }
        return new LeaveResult(teamName, previousOwner.equals(player) ? newOwner : null, false);
    }

    public void kick(final UUID actor, final UUID target, final boolean staff) {
        if (Owner.isOwner(target)) {
            throw new IllegalArgumentException("The server owner cannot be kicked from a team.");
        }
        final StoredTeam team = store.byMember(target)
                .orElseThrow(() -> new IllegalArgumentException("That player is not in a team."));
        if (!staff && !team.owner().equals(actor)) {
            throw new IllegalArgumentException("Only the team owner can kick members.");
        }
        if (team.owner().equals(target)) {
            throw new IllegalArgumentException("The owner cannot be kicked.");
        }
        if (!team.members().contains(target)) {
            throw new IllegalArgumentException("That player is not in your team.");
        }
        store.removeMember(team, target);
        if (!store.save(warn)) {
            store.addMember(team, target);
            throw new IllegalStateException("Could not save the team change.");
        }
    }

    public List<UUID> members(final Team team) {
        return team.members();
    }

    public void handleQuit(final UUID id) {
        invitations.entrySet().removeIf(entry ->
                entry.getKey().equals(id) || entry.getValue().inviter().equals(id));
    }

    public void close() {
        invitations.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        handleQuit(event.getPlayer().getUniqueId());
    }

    private void purgeExpired(final long now) {
        invitations.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private Team view(final StoredTeam team) {
        return new Team(
                team.id(),
                team.name(),
                team.owner(),
                List.copyOf(team.members()));
    }

    private static String validateName(final String raw) {
        final String name = raw == null ? "" : raw.trim();
        if (!TeamStore.NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Team names must be 1-20 characters: letters, numbers, '_', '+' or '-'.");
        }
        return name;
    }
}
