package io.github.near.sweats.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mindrot.jbcrypt.BCrypt;

class AuthStoreTest {

    @TempDir
    Path folder;

    private AuthStore store;

    private AuthStore open() throws Exception {
        store = new AuthStore(folder.resolve("auth.db"), message -> {});
        store.open();
        return store;
    }

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void storesAndFindsAnAccount() throws Exception {
        final AuthStore auth = open();
        final UUID id = UUID.randomUUID();
        final String hash = PasswordHasher.hash("a long password");

        assertTrue(auth.create(id, "Near", hash, "127.0.0.1", 1_000L));
        assertFalse(auth.create(id, "Near", hash, "127.0.0.1", 2_000L));
        assertTrue(auth.exists(id));
        assertEquals(1, auth.countAccounts());

        final AuthStore.Account account = auth.find(id).orElseThrow();
        assertEquals("Near", account.lastName());
        assertEquals("local", account.source());
        assertTrue(account.hasPassword());
        assertTrue(PasswordHasher.verify("a long password", account.passwordHash()));
        assertTrue(auth.findByName("near").isPresent());
        assertEquals(id, auth.findByName("NEAR").orElseThrow().uuid());
    }

    @Test
    void aNameSharedByTwoAccountsIsAmbiguousInsteadOfPickingOne() throws Exception {
        final AuthStore auth = open();
        auth.create(UUID.randomUUID(), "Reused", PasswordHasher.hash("first-password"),
                "127.0.0.1", 1_000L);
        auth.create(UUID.randomUUID(), "Reused", PasswordHasher.hash("second-password"),
                "127.0.0.2", 2_000L);

        assertTrue(auth.findByName("Reused").isEmpty());
    }

    @Test
    void wrongPasswordsLockTheAccountAndASuccessClearsIt() throws Exception {
        final AuthStore auth = open();
        final UUID id = UUID.randomUUID();
        auth.create(id, "Near", PasswordHasher.hash("password1"), "127.0.0.1", 1_000L);

        final AuthStore.Failure first = auth.recordFailure(id, 3, 60_000L, 2_000L).orElseThrow();
        assertEquals(1, first.attempts());
        assertEquals(0L, first.lockedUntil());

        auth.recordFailure(id, 3, 60_000L, 2_000L);
        final AuthStore.Failure third = auth.recordFailure(id, 3, 60_000L, 2_000L).orElseThrow();
        assertEquals(3, third.attempts());
        assertEquals(62_000L, third.lockedUntil());

        auth.recordLogin(id, "Near", "127.0.0.1", 3_000L);
        final AuthStore.Account account = auth.find(id).orElseThrow();
        assertEquals(0, account.failedAttempts());
        assertEquals(0L, account.lockedUntil());
        assertEquals(3_000L, account.lastLoginAt());
    }

    @Test
    void sessionsExpireAndCanBeCleared() throws Exception {
        final AuthStore auth = open();
        final UUID id = UUID.randomUUID();
        auth.create(id, "Near", PasswordHasher.hash("password1"), "127.0.0.1", 1_000L);

        auth.saveSession(id, "10.0.0.5", 5_000L);
        assertEquals("10.0.0.5", auth.session(id).orElseThrow().ip());

        auth.saveSession(id, "10.0.0.6", 9_000L);
        assertEquals("10.0.0.6", auth.session(id).orElseThrow().ip());

        auth.clearExpiredSessions(5_500L);
        assertTrue(auth.session(id).isPresent());
        auth.clearExpiredSessions(9_500L);
        assertTrue(auth.session(id).isEmpty());

        auth.saveSession(id, "10.0.0.6", 9_000L);
        auth.clearSession(id);
        assertTrue(auth.session(id).isEmpty());
    }

    @Test
    void aSavedLocationIsNeverOverwrittenByTheLoginRoom() throws Exception {
        final AuthStore auth = open();
        final UUID id = UUID.randomUUID();

        auth.savePendingIfAbsent(id, AuthStore.StoredLocation.of("world", 100.0, 70.0, -20.0, 45.0f, 10.0f));
        auth.savePendingIfAbsent(id, AuthStore.StoredLocation.of("world", 0.0, 100.0, 0.0, 0.0f, 0.0f));

        final AuthStore.StoredLocation stored = auth.peekPending(id).orElseThrow();
        assertEquals(100.0, stored.x());
        assertEquals(70.0, stored.y());
        assertEquals(-20.0, stored.z());

        auth.deletePending(id);
        assertTrue(auth.peekPending(id).isEmpty());
    }

    @Test
    void importsSimpleLoginHashesWithoutOverwritingChangedPasswords() throws Exception {
        final Path legacyFile = folder.resolve("SimpleLogin").resolve("passwords.db");
        java.nio.file.Files.createDirectories(legacyFile.getParent());

        final UUID id = UUID.randomUUID();
        final String legacyHash = BCrypt.hashpw("simplelogin-password", BCrypt.gensalt(4));

        try (Connection legacy = DriverManager.getConnection("jdbc:sqlite:" + legacyFile);
                Statement statement = legacy.createStatement()) {
            statement.execute("CREATE TABLE users (uuid TEXT PRIMARY KEY, password TEXT, secret TEXT)");
            try (PreparedStatement insert = legacy.prepareStatement(
                    "INSERT INTO users (uuid, password, secret) VALUES (?, ?, '')")) {
                insert.setString(1, id.toString());
                insert.setString(2, legacyHash);
                insert.executeUpdate();
            }
        }

        final AuthStore auth = open();
        assertEquals(1, auth.importSimpleLogin(legacyFile, 1_000L));
        assertEquals(0, auth.importSimpleLogin(legacyFile, 2_000L));

        final AuthStore.Account account = auth.find(id).orElseThrow();
        assertEquals("simplelogin", account.source());
        assertTrue(PasswordHasher.verify("simplelogin-password", account.passwordHash()));

        // A player changed their password in the new system; a later import must not undo it.
        auth.updatePassword(id, PasswordHasher.hash("new-password"));
        assertEquals(0, auth.importSimpleLogin(legacyFile, 3_000L));
        assertTrue(PasswordHasher.verify("new-password", auth.find(id).orElseThrow().passwordHash()));
    }

    @Test
    void aMissingLegacyDatabaseIsNotAnError() throws Exception {
        assertEquals(0, open().importSimpleLogin(folder.resolve("missing.db"), 1_000L));
    }

    @Test
    void aBrokenLegacyDatabaseReportsFailureInsteadOfZeroRows() throws Exception {
        final Path broken = folder.resolve("broken.db");
        Files.writeString(broken, "this is not a sqlite database");

        assertEquals(-1, open().importSimpleLogin(broken, 1_000L));
    }

    @Test
    void passwordUpdatesReportWhetherAnAccountChanged() throws Exception {
        final AuthStore auth = open();
        final UUID id = UUID.randomUUID();
        auth.create(id, "Near", PasswordHasher.hash("old-password"), "127.0.0.1", 1_000L);

        assertTrue(auth.updatePassword(id, PasswordHasher.hash("new-password")));
        assertTrue(PasswordHasher.verify(
                "new-password", auth.find(id).orElseThrow().passwordHash()));
        assertFalse(auth.updatePassword(UUID.randomUUID(), PasswordHasher.hash("new-password")));
    }

    @Test
    void aStaleHashUpgradeCannotOverwriteANewerPassword() throws Exception {
        final AuthStore auth = open();
        final UUID id = UUID.randomUUID();
        auth.create(id, "Near", PasswordHasher.hash("old-password"), "127.0.0.1", 1_000L);
        final String verified = auth.find(id).orElseThrow().passwordHash();

        assertTrue(auth.updatePasswordIfUnchanged(
                id, verified, PasswordHasher.hash("new-password")));
        assertFalse(auth.updatePasswordIfUnchanged(
                id, verified, PasswordHasher.hash("stale-upgrade")));
        assertTrue(PasswordHasher.verify(
                "new-password", auth.find(id).orElseThrow().passwordHash()));
    }

    @Test
    void loginThrottleIsSharedByAccountAndAddressAndSurvivesAReopen() throws Exception {
        AuthStore auth = open();
        final UUID id = UUID.randomUUID();
        final UUID other = UUID.randomUUID();

        assertTrue(auth.acquireAttempt(id, "10.0.0.1", 1_000L, 750L));
        assertFalse(auth.acquireAttempt(id, "10.0.0.2", 1_100L, 750L));
        assertFalse(auth.acquireAttempt(other, "10.0.0.1", 1_100L, 750L));

        // The rejected attempt must not have moved either timestamp.
        assertTrue(auth.acquireAttempt(id, "10.0.0.2", 1_800L, 750L));

        auth.close();
        auth = new AuthStore(folder.resolve("auth.db"), message -> {});
        auth.open();
        store = auth;

        assertFalse(auth.acquireAttempt(id, "10.0.0.3", 2_000L, 750L));
        assertTrue(auth.acquireAttempt(id, "10.0.0.3", 2_551L, 750L));
        assertTrue(auth.acquireAttempt(UUID.randomUUID(), "10.0.0.4", 1L, 0L));
    }

    @Test
    void staleThrottleRowsAreDroppedOnceTheyCannotBlockAnAttempt() throws Exception {
        final AuthStore auth = open();
        final UUID id = UUID.randomUUID();

        assertTrue(auth.acquireAttempt(id, "10.0.0.9", 1_000L, 60_000L));
        assertFalse(auth.acquireAttempt(id, "10.0.0.9", 2_000L, 60_000L));

        auth.clearStaleThrottles(1_500L);
        assertTrue(auth.acquireAttempt(id, "10.0.0.9", 2_000L, 60_000L));
    }

    @Test
    void remembersThePremiumTipOptOut() throws Exception {
        final AuthStore auth = open();
        final UUID id = UUID.randomUUID();

        assertFalse(auth.isPremiumPromptOff(id));

        auth.setPremiumPromptOff(id, true);
        assertTrue(auth.isPremiumPromptOff(id));
        assertEquals(1, auth.countAccounts());

        auth.setPremiumPromptOff(id, false);
        assertFalse(auth.isPremiumPromptOff(id));
    }

    @Test
    void anAdminPasswordResetAlsoStoresThePlayerName() throws Exception {
        final AuthStore auth = open();
        final UUID id = UUID.nameUUIDFromBytes(
                "OfflinePlayer:Forgotten".getBytes(StandardCharsets.UTF_8));
        auth.create(id, null, PasswordHasher.hash("old-password"), "127.0.0.1", 1_000L);

        assertTrue(auth.findByName("Forgotten").isEmpty());
        assertTrue(auth.setPasswordAndName(id, "Forgotten", PasswordHasher.hash("new-password")));

        assertEquals(id, auth.findByName("forgotten").orElseThrow().uuid());
        assertTrue(PasswordHasher.verify(
                "new-password", auth.find(id).orElseThrow().passwordHash()));
    }

    @Test
    void importFlagsSurviveAReopen() throws Exception {
        AuthStore auth = open();
        assertFalse(auth.flag("import.bundled"));

        auth.setMeta("import.bundled", "true");
        assertTrue(auth.flag("import.bundled"));

        auth.close();
        auth = new AuthStore(folder.resolve("auth.db"), message -> {});
        auth.open();
        store = auth;
        assertTrue(auth.flag("import.bundled"));
    }

    @Test
    void upgradesAnAuthDatabaseFromThePreviousLayout() throws Exception {
        final Path file = folder.resolve("auth.db");
        final UUID id = UUID.nameUUIDFromBytes(
                "OfflinePlayer:Old".getBytes(StandardCharsets.UTF_8));

        try (Connection legacy = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = legacy.createStatement()) {
            statement.execute("""
                    CREATE TABLE accounts (
                        uuid TEXT PRIMARY KEY,
                        password_hash TEXT NOT NULL,
                        last_name TEXT,
                        premium INTEGER NOT NULL DEFAULT 0,
                        premium_checked_at INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'local',
                        registered_at INTEGER NOT NULL,
                        last_login_at INTEGER NOT NULL DEFAULT 0,
                        last_ip TEXT,
                        failed_attempts INTEGER NOT NULL DEFAULT 0,
                        locked_until INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            try (PreparedStatement insert = legacy.prepareStatement(
                    "INSERT INTO accounts (uuid, password_hash, registered_at) VALUES (?, ?, 1)")) {
                insert.setString(1, id.toString());
                insert.setString(2, PasswordHasher.hash("old-password"));
                insert.executeUpdate();
            }
        }

        store = new AuthStore(file, message -> {});
        store.open();

        final AuthStore.Account account = store.find(id).orElseThrow();
        assertFalse(account.premiumPromptOff());
        store.setPremiumPromptOff(id, true);
        assertTrue(store.isPremiumPromptOff(id));
    }
}
