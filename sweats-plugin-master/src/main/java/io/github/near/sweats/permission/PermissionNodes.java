package io.github.near.sweats.permission;

import io.github.near.sweats.perks.RankPerks;
import io.github.near.sweats.rank.Rank;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class PermissionNodes {

    public static final String BAN = "sweats.banperm";
    public static final String KICK = "sweats.kickperm";
    public static final String MUTE = "sweats.muteperm";
    public static final String UNBAN = "sweats.unbanperm";
    public static final String UNMUTE = "sweats.unmuteperm";
    public static final String HISTORY = "sweats.historyperm";
    public static final String RANK_MANAGE = "sweats.rank.manage";
    public static final String PERMISSION_MANAGE = "sweats.permission.manage";
    public static final String NICK = "sweats.nick";
    public static final String NICK_OTHERS = "sweats.nick.others";
    public static final String LEADERBOARD = "sweats.leaderboard";
    public static final String HOME = "sweats.home";
    public static final String HOMESET = "sweats.homeset";
    public static final String VANISH = "sweats.vanish";
    public static final String TEAM_CREATE = "sweats.team.create";
    public static final String TEAM_DELETE = "sweats.team.delete";
    public static final String TEAM_INVITE = "sweats.team.invite";
    public static final String TEAM_MANAGE = "sweats.team.manage";
    public static final String TEAM_CHAT = "sweats.team.chat";
    public static final String TPA = "sweats.tpa";
    public static final String TPA_BYPASS_COOLDOWN = "sweats.tpa.bypasscooldown";
    public static final String BETA_ACCESS = "sweats.beta.access";
    public static final String CHAT_WHITE = "sweats.chat.white";
    public static final String CHAT_MEMBER = "sweats.chat.member";
    public static final String PLUS_COLOR = "sweats.rank.pluscolor";
    public static final String LIST = "sweats.list";
    public static final String SHOW = "sweats.show";
    public static final String STATS = "sweats.stats";
    public static final String SCARE = "sweats.scare";
    public static final String GIF = "sweats.gif";
    public static final String CUSTOM_RANK = "sweats.rank.custom";
    public static final String INVESTOR_DISCORD = "sweats.investor.discord";
    public static final String RUNES = "sweats.runes";
    public static final String RUNES_ADMIN = "sweats.runes.admin";
    public static final String RUNETABLE = "sweats.runetable";
    public static final String FRIEND = "sweats.friend";
    public static final String FRIEND_REQUESTS = "sweats.friend.requests";
    public static final String CURRENCY_GIVE = "sweats.currency.give";
    public static final String SCOREBOARD_TOGGLE = "sweats.scoreboard.toggle";
    public static final String SCOREBOARD_ADMIN = "sweats.scoreboard.admin";
    public static final String MESSAGE = "sweats.message";
    public static final String MESSAGE_BYPASS_COOLDOWN = "sweats.message.bypasscooldown";
    public static final String DUPE_ALERTS = "sweats.dupe.alerts";

    /** Every node this plugin registers, used for /grant tab completion. */
    public static final List<String> ALL = List.of(
            "sweats.admin",
            "sweats.auth.admin",
            "sweats.banperm",
            "sweats.beta.access",
            "sweats.chat.member",
            "sweats.chat.white",
            "sweats.currency.give",
            "sweats.dupe.alerts",
            "sweats.friend",
            "sweats.friend.requests",
            "sweats.gif",
            "sweats.historyperm",
            "sweats.home",
            "sweats.homeset",
            "sweats.kickperm",
            "sweats.leaderboard",
            "sweats.list",
            "sweats.maintenance",
            "sweats.maintenance.bypass",
            "sweats.message",
            "sweats.message.bypasscooldown",
            "sweats.muteperm",
            "sweats.nick",
            "sweats.nick.others",
            "sweats.permission.manage",
            "sweats.ping",
            "sweats.ping.others",
            "sweats.ram",
            "sweats.rank.custom",
            "sweats.rank.manage",
            "sweats.rank.pluscolor",
            "sweats.runes",
            "sweats.runes.admin",
            "sweats.runetable",
            "sweats.scoreboard.admin",
            "sweats.scoreboard.toggle",
            "sweats.scare",
            "sweats.scare.bypass",
            "sweats.show",
            "sweats.show.bypass",
            "sweats.stats",
            "sweats.team.chat",
            "sweats.team.create",
            "sweats.team.delete",
            "sweats.team.invite",
            "sweats.team.manage",
            "sweats.tpa",
            "sweats.tpa.bypasscooldown",
            "sweats.unbanperm",
            "sweats.unmuteperm",
            "sweats.vanish",
            "sweats.vote.admin",
            "sweats.investor.discord");

    private static final Pattern NODE_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");

    private PermissionNodes() {}

    /**
     * Capabilities that follow from the rank itself. These are applied to the player's permission
     * attachment but never written to permissions.yml, so rank changes cannot leak or delete
     * manually granted nodes.
     */
    public static Set<String> derivedFor(final Rank rank) {
        final RankPerks perks = RankPerks.of(rank);
        final Set<String> nodes = new LinkedHashSet<>();
        // Base permissions for all ranks
        nodes.add(FRIEND);
        nodes.add(FRIEND_REQUESTS);
        if (perks.whiteChat()) {
            nodes.add(CHAT_WHITE);
        }
        if (perks.memberChat()) {
            nodes.add(CHAT_MEMBER);
        }
        if (perks.betaAccess()) {
            nodes.add(BETA_ACCESS);
        }
        if (perks.plusColor()) {
            nodes.add(PLUS_COLOR);
        }
        if (perks.showAccess()) {
            nodes.add(SHOW);
        }
        if (perks.statsAccess()) {
            nodes.add(STATS);
        }
        if (perks.nickAccess()) {
            nodes.add(NICK);
        }
        if (perks.scareAccess()) {
            nodes.add(SCARE);
        }
        if (perks.gifAccess()) {
            nodes.add(GIF);
        }
        if (perks.customRankName()) {
            nodes.add(CUSTOM_RANK);
        }
        if (perks.highestNonStaffTabPriority()) {
            nodes.add(INVESTOR_DISCORD);
        }
        if ("dev".equals(rank.key())) {
            nodes.add(VANISH);
        }
        if ("cm".equals(rank.key()) || "dev".equals(rank.key())) {
            nodes.add(CURRENCY_GIVE);
        }
        return nodes;
    }

    public static boolean isValid(final String node) {
        return node != null
                && !node.isBlank()
                && node.length() <= 100
                && NODE_PATTERN.matcher(node).matches();
    }
}