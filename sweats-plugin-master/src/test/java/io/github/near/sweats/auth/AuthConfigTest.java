package io.github.near.sweats.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AuthConfigTest {

    @Test
    void missingFileFallsBackToSafeDefaults() {
        final AuthConfig config = AuthConfig.from(new YamlConfiguration());

        assertTrue(config.enabled());
        assertTrue(config.useWorldSpawn());
        assertEquals("", config.world());
        assertEquals(8, config.minPasswordLength());
        assertEquals(128, config.maxPasswordLength());
        assertEquals(300, config.loginTimeoutSeconds());
        assertEquals(AuthConfig.PremiumMode.PROXY, config.premiumMode());
        assertTrue(config.premiumVerifyMojang());
        assertTrue(config.premiumSuggestion());
        assertTrue(config.sessionBindIp());
        assertEquals(168L * 3_600_000L, config.sessionDurationMillis());
        assertTrue(config.importSimpleLogin());
    }

    @Test
    void readsTheDocumentedKeys() throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                auth:
                  enabled: false
                  location:
                    world: "login"
                    use-world-spawn: false
                    x: 10.5
                    y: 65.0
                    z: -3.5
                    yaw: 90.0
                    pitch: 15.0
                  min-password-length: 12
                  max-password-length: 40
                  login-timeout-seconds: 60
                  reminder-seconds: 2
                  max-failed-attempts: 3
                  lockout-seconds: 30
                  attempt-cooldown-ms: 1500
                  blindness: false
                  hide-players: false
                  block-chat: false
                  block-commands: false
                  session:
                    enabled: false
                    duration-hours: 2
                    bind-ip: false
                  premium:
                    enabled: false
                    mode: mojang
                    verify-mojang: false
                    suggestion: false
                  import-simplelogin: false
                  simplelogin-database: "/srv/old/passwords.db"
                """);

        final AuthConfig config = AuthConfig.from(yaml);

        assertFalse(config.enabled());
        assertEquals("login", config.world());
        assertFalse(config.useWorldSpawn());
        assertEquals(10.5, config.x());
        assertEquals(65.0, config.y());
        assertEquals(-3.5, config.z());
        assertEquals(90.0f, config.yaw());
        assertEquals(12, config.minPasswordLength());
        assertEquals(40, config.maxPasswordLength());
        assertEquals(60, config.loginTimeoutSeconds());
        assertEquals(2, config.reminderSeconds());
        assertEquals(3, config.maxFailedAttempts());
        assertEquals(30, config.lockoutSeconds());
        assertEquals(1500L, config.attemptCooldownMillis());
        assertFalse(config.blindness());
        assertFalse(config.hidePlayers());
        assertFalse(config.blockChat());
        assertFalse(config.blockCommands());
        assertFalse(config.sessionEnabled());
        assertEquals(2L * 3_600_000L, config.sessionDurationMillis());
        assertFalse(config.sessionBindIp());
        assertFalse(config.premiumEnabled());
        assertEquals(AuthConfig.PremiumMode.MOJANG, config.premiumMode());
        assertFalse(config.premiumVerifyMojang());
        assertFalse(config.premiumSuggestion());
        assertFalse(config.importSimpleLogin());
        assertEquals("/srv/old/passwords.db", config.simpleLoginDatabase());
    }

    @Test
    void clampsNonsenseValuesInsteadOfCrashingTheLoginGate() throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                auth:
                  min-password-length: -5
                  max-password-length: 2
                  login-timeout-seconds: 999999999
                  max-failed-attempts: 0
                  session:
                    duration-hours: -3
                  premium:
                    mode: "something-else"
                """);

        final AuthConfig config = AuthConfig.from(yaml);

        assertEquals(4, config.minPasswordLength());
        assertEquals(4, config.maxPasswordLength());
        assertEquals(86_400, config.loginTimeoutSeconds());
        assertEquals(1, config.maxFailedAttempts());
        assertEquals(0L, config.sessionDurationMillis());
        assertEquals(AuthConfig.PremiumMode.PROXY, config.premiumMode());
    }

    @Test
    void messagesUseMiniMessageAndPlaceholders() {
        final AuthConfig config = AuthConfig.from(new YamlConfiguration());
        final String text = PlainTextComponentSerializer.plainText()
                .serialize(config.messages().component("login-failed", "attempts", "3"));

        assertTrue(text.contains("3 attempt(s) left."));
    }

    @Test
    void thePremiumTipContainsTheCommandAndTheOptOut() {
        final String text = PlainTextComponentSerializer.plainText()
                .serialize(AuthConfig.from(new YamlConfiguration())
                        .messages()
                        .component("premium-suggestion"));

        assertTrue(text.contains("Use /premium if you have a Minecraft account"));
        assertTrue(text.contains("[Click this so it doesn't send this next time]"));
    }
}
