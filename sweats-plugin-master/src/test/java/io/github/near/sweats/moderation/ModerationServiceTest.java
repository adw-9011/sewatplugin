package io.github.near.sweats.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModerationServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private long now = 1_700_000_000_000L;

    @Test
    void bansUseExpiryTimestampsAndSurviveReloads() {
        final UUID id = UUID.randomUUID();
        final ModerationService service = service();
        final Punishment ban = service.ban(
                id, "Alex", DurationParser.parse("10m"), "cheating", new Issuer(null, "Console"));

        assertTrue(service.activeBan(id).isPresent());
        assertEquals("ACTIVE", ban.status(now));
        assertThrows(ModerationException.class, () -> service.ban(
                id, "Alex", DurationParser.parse("10m"), "again", new Issuer(null, "Console")));

        final ModerationService reloaded = service();
        assertTrue(reloaded.activeBan(id).isPresent());
        assertEquals(List.of(ban.id()), reloaded.history(id).stream().map(Punishment::id).toList());

        final Punishment revoked = reloaded.unban(id);
        assertTrue(revoked.revoked());
        assertTrue(reloaded.activeBan(id).isEmpty());
        assertEquals("REVOKED", revoked.status(now));
        assertThrows(ModerationException.class, () -> reloaded.unban(id));

        final ModerationService finalReload = service();
        assertTrue(finalReload.activeBan(id).isEmpty());
        assertEquals(1, finalReload.history(id).size());
    }

    @Test
    void expiredPunishmentsStopApplyingWithoutAScheduledTask() {
        final UUID id = UUID.randomUUID();
        final ModerationService service = service();
        final Punishment mute = service.mute(
                id, "Alex", DurationParser.parse("30s"), "spam", new Issuer(null, "Console"));

        now += 29_999L;
        assertTrue(service.activeMute(id).isPresent());
        assertEquals("ACTIVE", mute.status(now));

        now += 1L;
        assertTrue(service.activeMute(id).isEmpty());
        assertFalse(mute.active(now));
        assertEquals("EXPIRED", mute.status(now));
        assertTrue(service.findActiveByName("Alex", PunishmentType.MUTE).isEmpty());
    }

    @Test
    void permanentMutesStayUntilUnmuted() {
        final UUID id = UUID.randomUUID();
        final ModerationService service = service();
        final Punishment mute = service.mute(
                id,
                "Alex",
                PunishmentDuration.ofPermanent(),
                "spam",
                new Issuer(null, "Console"));

        now += 10L * 365L * 24L * 60L * 60L * 1000L;
        assertTrue(service.activeMute(id).isPresent());
        assertEquals("Permanent", DurationFormat.remaining(mute, now));

        final Punishment revoked = service.unmute(id);
        assertTrue(revoked.revoked());
        assertTrue(service.activeMute(id).isEmpty());
    }

    @Test
    void kicksAreRecordedButNeverActive() {
        final UUID id = UUID.randomUUID();
        final ModerationService service = service();

        final Punishment kick = service.kick(
                id, "Alex", "rudeness", new Issuer(UUID.randomUUID(), "Moderator"));

        assertFalse(kick.active(now));
        assertEquals("RECORDED", kick.status(now));
        assertEquals("rudeness", kick.reason());
        assertEquals("Moderator", kick.issuerName());
        assertEquals(1, service.history(id).size());
        assertTrue(service.activeBan(id).isEmpty());
        assertTrue(service.activeMute(id).isEmpty());
    }

    @Test
    void activeNameLookupsAreCaseInsensitive() {
        final UUID id = UUID.randomUUID();
        final ModerationService service = service();
        service.ban(id, "Alex", DurationParser.parse("1h"), "cheating", new Issuer(null, "Console"));

        assertTrue(service.findActiveByName("ALEX", PunishmentType.BAN).isPresent());
        assertEquals(List.of("Alex"), service.activeNames(PunishmentType.BAN));
    }

    @Test
    void aFailedBanSaveRollsBackTheInMemoryState() throws Exception {
        final Path path = blockedPunishmentFile();
        final ModerationService service = service();
        final UUID id = UUID.randomUUID();

        assertThrows(ModerationException.class, () -> service.ban(
                id, "Alex", DurationParser.parse("10m"), "cheating", new Issuer(null, "Console")));

        assertTrue(service.activeBan(id).isEmpty());
        assertTrue(service.history(id).isEmpty());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("punishments.yml")));
        assertTrue(Files.isDirectory(path));
    }

    @Test
    void aFailedUnbanSaveKeepsTheBanActive() throws Exception {
        final UUID id = UUID.randomUUID();
        final ModerationService service = service();
        service.ban(id, "Alex", DurationParser.parse("10m"), "cheating", new Issuer(null, "Console"));

        final Path path = folder.resolve("punishments.yml");
        Files.delete(path);
        blockedPunishmentFile();

        assertThrows(ModerationException.class, () -> service.unban(id));
        assertTrue(service.activeBan(id).isPresent());
        assertEquals(1, service.history(id).size());
    }

    private Path blockedPunishmentFile() throws Exception {
        final Path path = folder.resolve("punishments.yml");
        Files.createDirectories(path);
        Files.writeString(path.resolve("block"), "not a yaml file");
        return path;
    }

    private ModerationService service() {
        return new ModerationService(null, folder, warnings::add, () -> now);
    }
}
