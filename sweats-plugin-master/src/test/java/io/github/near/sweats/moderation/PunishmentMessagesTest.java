package io.github.near.sweats.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class PunishmentMessagesTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void muteUsesTheExactRequiredLayout() {
        final Punishment mute = mute("spam", NOW + 90_000L);

        assertEquals("""
                ====================================
                You are muted, you can't send any messages!

                Mute duration: 1m 30s
                Reason: spam
                ====================================""",
                TestCommandSenders.plain(PunishmentMessages.mute(mute, NOW)));
    }

    @Test
    void permanentMuteShowsPermanent() {
        final Punishment mute = new Punishment(
                UUID.randomUUID(), PunishmentType.MUTE, UUID.randomUUID(), "Alex",
                null, "Console", "spam", NOW, null, false);

        final String plain = TestCommandSenders.plain(PunishmentMessages.mute(mute, NOW));

        assertTrue(plain.contains("Mute duration: Permanent"));
        assertTrue(plain.contains("Reason: spam"));
    }

    @Test
    void banAndKickIncludeTheReason() {
        final Punishment ban = new Punishment(
                UUID.randomUUID(), PunishmentType.BAN, UUID.randomUUID(), "Alex",
                null, "Console", "cheating", NOW, NOW + 600_000L, false);
        final Punishment kick = new Punishment(
                UUID.randomUUID(), PunishmentType.KICK, UUID.randomUUID(), "Alex",
                null, "Console", "rudeness", NOW, null, false);

        assertTrue(TestCommandSenders.plain(PunishmentMessages.ban(ban, NOW))
                .contains("Reason: cheating"));
        assertTrue(TestCommandSenders.plain(PunishmentMessages.ban(ban, NOW))
                .contains("Duration: 10m"));
        assertEquals(NamedTextColor.RED, TestCommandSenders.colorOf(
                PunishmentMessages.ban(ban, NOW), "You are banned from Sweats."));
        assertTrue(TestCommandSenders.plain(PunishmentMessages.kick(kick))
                .contains("Reason: rudeness"));
    }

    private static Punishment mute(final String reason, final Long expiresAt) {
        return new Punishment(
                UUID.randomUUID(), PunishmentType.MUTE, UUID.randomUUID(), "Alex",
                null, "Console", reason, NOW, expiresAt, false);
    }
}
