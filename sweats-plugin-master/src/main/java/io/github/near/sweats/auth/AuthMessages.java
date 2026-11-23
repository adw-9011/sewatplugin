package io.github.near.sweats.auth;

import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Configurable login messages.
 *
 * <p>Values use MiniMessage syntax. Placeholders such as {@code <seconds>} are replaced before
 * the text is parsed, so plugins that write their own MiniMessage tags keep working.
 */
public final class AuthMessages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("login-required", "<yellow>Please log in.</yellow> <gray>Use <white>/login</white> and type your password in chat.</gray>"),
            Map.entry("register-required", "<yellow>Please register.</yellow> <gray>Use <white>/register</white> and type a password in chat.</gray>"),
            Map.entry("login-prompt", "<yellow>Type your password in chat to log in.</yellow> <gray>Type <white>cancel</white> to stop.</gray>"),
            Map.entry("login-success", "<green>Logged in. Welcome back!</green>"),
            Map.entry("login-failed", "<red>Wrong password.</red> <gray><attempts> attempt(s) left.</gray>"),
            Map.entry("login-changed", "<red>Your password changed while you were logging in.</red> <gray>Please try again.</gray>"),
            Map.entry("login-locked", "<red>Too many wrong passwords.</red> <gray>Try again in <seconds> seconds.</gray>"),
            Map.entry("login-cooldown", "<red>Slow down.</red> <gray>Wait a moment before trying again.</gray>"),
            Map.entry("register-prompt", "<yellow>Type a password in chat to create your account.</yellow> <gray>At least <length> characters.</gray>"),
            Map.entry("register-cooldown", "<red>Slow down.</red> <gray>Wait a moment before trying to register again.</gray>"),
            Map.entry("register-confirm", "<yellow>Type the same password again.</yellow>"),
            Map.entry("register-success", "<green>Account created and logged in. Welcome!</green>"),
            Map.entry("register-mismatch", "<red>The passwords do not match.</red> <gray>Start again with /register.</gray>"),
            Map.entry("register-too-short", "<red>That password is too short.</red> <gray>Use at least <length> characters.</gray>"),
            Map.entry("password-too-long", "<red>That password is too long.</red> <gray>Use at most <length> characters.</gray>"),
            Map.entry("password-is-name", "<red>Choose a password that is not your player name.</red>"),
            Map.entry("already-registered", "<red>That account is already registered.</red> <gray>Use /login.</gray>"),
            Map.entry("not-registered", "<red>That account is not registered.</red> <gray>Use /register.</gray>"),
            Map.entry("already-authenticated", "<gray>You are already logged in.</gray>"),
            Map.entry("not-logged-in", "<gray>You are not logged in.</gray>"),
            Map.entry("auth-disabled", "<red>Authentication is disabled on this server.</red>"),
            Map.entry("auth-unavailable", "<red>Authentication is temporarily unavailable.</red> <gray>Contact an administrator.</gray>"),
            Map.entry("auth-busy", "<red>The login service is busy.</red> <gray>Please try again in a moment.</gray>"),
            Map.entry("auth-save-failed", "<red>Could not save the password.</red> <gray>Nothing was changed; try again or contact an administrator.</gray>"),
            Map.entry("chat-blocked", "<red>You cannot chat before logging in.</red>"),
            Map.entry("command-blocked", "<red>You cannot use that command before logging in.</red>"),
            Map.entry("logout-success", "<yellow>Logged out.</yellow> <gray>You were moved back to the login area.</gray>"),
            Map.entry("session-restored", "<gray>Welcome back. Your session was restored.</gray>"),
            Map.entry("password-command-warning", "<gray>Tip: use <white>/login</white> without arguments next time so the password is not written to the server log.</gray>"),
            Map.entry("password-changed", "<green>Password changed.</green>"),
            Map.entry("password-wrong", "<red>Wrong password.</red>"),
            Map.entry("password-current", "<yellow>Type your current password in chat.</yellow>"),
            Map.entry("password-new", "<yellow>Type your new password in chat.</yellow>"),
            Map.entry("premium-status", "<gray>Premium login: <white><status></white></gray>"),
            Map.entry("premium-disabled", "<gray>Premium login is disabled on this server.</gray>"),
            Map.entry("premium-verified", "<green>Verified with Mojang. You can join without a password from now on.</green>"),
            Map.entry("premium-login", "<green>Premium account recognised. Welcome!</green>"),
            Map.entry("premium-offline", "<red>This server is in offline mode, so premium login cannot be verified safely.</red> <gray>Use /login. Premium bypass requires online-mode or a proxy with forwarding.</gray>"),
            Map.entry("premium-unavailable", "<red>Could not verify that account with Mojang right now.</red> <gray>Try again later or log in with your password.</gray>"),
            Map.entry("premium-suggestion", "<aqua>Use <click:run_command:'/premium'><white><u>/premium</u></white></click> if you have a Minecraft account - you can skip the password.</aqua> <dark_gray>|</dark_gray> <gray><click:run_command:'/premium mute'><u>[Click this so it doesn't send this next time]</u></click></gray>"),
            Map.entry("premium-suggestion-muted", "<gray>Okay, the premium tip will not be shown again.</gray> <gray>Run <white>/premium unmute</white> to bring it back.</gray>"),
            Map.entry("premium-suggestion-enabled", "<gray>The premium tip will be shown again after your next login.</gray>"),
            Map.entry("admin-password-changed", "<green>Changed the password for <white><player></white>.</green>"),
            Map.entry("admin-password-not-found", "<red>No account found for <white><player></white>.</red> <gray>They need to join the server once first, or check the spelling.</gray>"),
            Map.entry("password-changed-by-admin", "<yellow>An admin changed your password.</yellow> <gray>Use the new one the next time you log in.</gray>"),
            Map.entry("timeout", "<red>You did not log in in time.</red>"),
            Map.entry("cancelled", "<gray>Login cancelled.</gray>"));

    private final ConfigurationSection section;

    private AuthMessages(final ConfigurationSection section) {
        this.section = section;
    }

    static AuthMessages from(final ConfigurationSection section) {
        return new AuthMessages(section);
    }

    /** Defaults, used by tests and when config.yml has no messages section. */
    public static AuthMessages defaults() {
        return new AuthMessages(null);
    }

    static Map<String, String> defaultValues() {
        return DEFAULTS;
    }

    /** Replaces {@code <key>} tokens, then parses the result as MiniMessage. */
    public Component component(final String key, final String... replacements) {
        final String template = section == null ? null : section.getString(key);
        String text = template == null ? DEFAULTS.getOrDefault(key, key) : template;

        if (replacements.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders must be key/value pairs");
        }
        for (int index = 0; index < replacements.length; index += 2) {
            text = text.replace("<" + replacements[index] + ">", replacements[index + 1]);
        }
        return MINI_MESSAGE.deserialize(text);
    }

    public void send(final Audience audience, final String key, final String... replacements) {
        audience.sendMessage(component(key, replacements));
    }
}
