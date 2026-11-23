package io.github.near.sweats.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SQLite-backed account, session and pending-location storage.
 *
 * <p>All access is serialised on one connection. Password hashing happens elsewhere, so the
 * short queries here never block on cryptography. WAL mode keeps reads from waiting for writes.
 */
public final class AuthStore implements AutoCloseable {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS accounts (
                uuid TEXT PRIMARY KEY,
                password_hash TEXT NOT NULL,
                last_name TEXT,
                premium INTEGER NOT NULL DEFAULT 0,
                premium_checked_at INTEGER NOT NULL DEFAULT 0,
                premium_prompt_off INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'local',
                registered_at INTEGER NOT NULL,
                last_login_at INTEGER NOT NULL DEFAULT 0,
                last_ip TEXT,
                failed_attempts INTEGER NOT NULL DEFAULT 0,
                locked_until INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS sessions (
                uuid TEXT PRIMARY KEY,
                ip TEXT NOT NULL,
                expires_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS pending_locations (
                uuid TEXT PRIMARY KEY,
                world TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                yaw REAL NOT NULL,
                pitch REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT
            );
            CREATE TABLE IF NOT EXISTS login_throttle (
                scope TEXT PRIMARY KEY,
                last_attempt_at INTEGER NOT NULL
            );
            """;

    private final Path file;
    private final Consumer<String> warn;
    private final Object lock = new Object();

    private Connection connection;

    public AuthStore(final Path file, final Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
    }

    public Path file() {
        return file;
    }

    public void open() throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (final IOException exception) {
            throw new SQLException("Could not create the auth database directory", exception);
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (final ClassNotFoundException exception) {
            throw new SQLException("The SQLite JDBC driver is not available", exception);
        }

        synchronized (lock) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                for (final String sql : SCHEMA.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
            migrate();
        }
    }

    /**
     * Adds columns that were introduced after the first release.
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} never changes an existing table, so a database from an
     * older build has to be upgraded in place. Duplicate-column errors are ignored on purpose: the
     * check below makes the migration idempotent, but two servers starting at once must not break.
     */
    private void migrate() {
        if (!hasColumn("accounts", "premium_prompt_off")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE accounts "
                        + "ADD COLUMN premium_prompt_off INTEGER NOT NULL DEFAULT 0");
            } catch (final SQLException exception) {
                warn.accept("Could not add the premium prompt column: " + exception.getMessage());
            }
        }
    }

    private boolean hasColumn(final String table, final String column) {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return true;
                }
            }
        } catch (final SQLException exception) {
            warn.accept("Could not inspect the auth database layout: " + exception.getMessage());
        }
        return false;
    }

    public boolean isOpen() {
        synchronized (lock) {
            return connection != null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (final SQLException exception) {
                warn.accept("Could not close auth.db: " + exception.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Accounts

    public Optional<Account> find(final UUID uuid) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, password_hash, last_name, premium, premium_checked_at, "
                            + "premium_prompt_off, source, registered_at, last_login_at, last_ip, "
                            + "failed_attempts, locked_until FROM accounts WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readAccount(result)) : Optional.empty();
                }
            } catch (final SQLException exception) {
                warn.accept("Could not read auth account: " + exception.getMessage());
                return Optional.empty();
            }
        }
    }

    /**
     * Looks an account up by the last name seen in game. Imported rows have no name until then.
     *
     * <p>Names are not unique: a name can be released and reused while an older account still
     * stores it as its last name. An ambiguous result is returned as empty so an admin command
     * cannot reset or delete the wrong account.
     */
    public Optional<Account> findByName(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, password_hash, last_name, premium, premium_checked_at, "
                            + "premium_prompt_off, source, registered_at, last_login_at, last_ip, "
                            + "failed_attempts, locked_until FROM accounts WHERE last_name = ? "
                            + "COLLATE NOCASE ORDER BY last_login_at DESC, registered_at DESC "
                            + "LIMIT 2")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    final Account account = readAccount(result);
                    return result.next() ? Optional.empty() : Optional.of(account);
                }
            } catch (final SQLException exception) {
                warn.accept("Could not look up auth account by name: " + exception.getMessage());
                return Optional.empty();
            }
        }
    }

    public boolean exists(final UUID uuid) {
        return find(uuid).isPresent();
    }

    /** Lightweight account identities, used to seed the real-player list for leaderboards. */
    public List<Account> allAccounts() {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, password_hash, last_name, premium, premium_checked_at, "
                            + "premium_prompt_off, source, registered_at, last_login_at, last_ip, "
                            + "failed_attempts, locked_until FROM accounts")) {
                try (ResultSet result = statement.executeQuery()) {
                    final List<Account> accounts = new ArrayList<>();
                    while (result.next()) {
                        accounts.add(readAccount(result));
                    }
                    return accounts;
                }
            } catch (final SQLException exception) {
                warn.accept("Could not list auth accounts: " + exception.getMessage());
                return List.of();
            }
        }
    }

    public boolean create(
            final UUID uuid,
            final String name,
            final String passwordHash,
            final String ip,
            final long now) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO accounts "
                            + "(uuid, password_hash, last_name, source, registered_at, last_ip) "
                            + "VALUES (?, ?, ?, 'local', ?, ?)")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, passwordHash);
                statement.setString(3, name);
                statement.setLong(4, now);
                statement.setString(5, ip);
                return statement.executeUpdate() > 0;
            } catch (final SQLException exception) {
                warn.accept("Could not create auth account: " + exception.getMessage());
                return false;
            }
        }
    }

    public boolean updatePassword(final UUID uuid, final String passwordHash) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE accounts SET password_hash = ? WHERE uuid = ?")) {
                statement.setString(1, passwordHash);
                statement.setString(2, uuid.toString());
                return statement.executeUpdate() > 0;
            } catch (final SQLException exception) {
                warn.accept("Could not update auth password: " + exception.getMessage());
                return false;
            }
        }
    }

    /**
     * Replaces a password only when the stored hash is still the one the caller verified.
     *
     * <p>A login upgrades a legacy BCrypt hash asynchronously. If a password change or an admin
     * reset commits while that hash is being computed, an unconditional update would silently
     * overwrite the newer password with one derived from the old one.
     *
     * @return true when the account existed with the expected hash and was updated
     */
    public boolean updatePasswordIfUnchanged(
            final UUID uuid, final String expectedHash, final String passwordHash) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE accounts SET password_hash = ? WHERE uuid = ? AND password_hash = ?")) {
                statement.setString(1, passwordHash);
                statement.setString(2, uuid.toString());
                statement.setString(3, expectedHash);
                return statement.executeUpdate() > 0;
            } catch (final SQLException exception) {
                warn.accept("Could not update auth password: " + exception.getMessage());
                return false;
            }
        }
    }

    /**
     * Sets a password and records the player name in one step.
     *
     * <p>Used by the admin {@code /changepass <player> <password>}: imported rows have no name
     * until their first login, and an admin may reset a password for an offline player who has
     * not logged in since the migration.
     *
     * @return true when an account existed and was updated
     */
    public boolean setPasswordAndName(
            final UUID uuid, final String name, final String passwordHash) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE accounts SET password_hash = ?, last_name = ?, failed_attempts = 0, "
                            + "locked_until = 0 WHERE uuid = ?")) {
                statement.setString(1, passwordHash);
                statement.setString(2, name);
                statement.setString(3, uuid.toString());
                return statement.executeUpdate() > 0;
            } catch (final SQLException exception) {
                warn.accept("Could not set the auth password: " + exception.getMessage());
                return false;
            }
        }
    }

    /** True when this account asked not to see the premium-login tip again. */
    public boolean isPremiumPromptOff(final UUID uuid) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT premium_prompt_off FROM accounts WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() && result.getInt(1) != 0;
                }
            } catch (final SQLException exception) {
                warn.accept("Could not read the premium prompt setting: " + exception.getMessage());
                return false;
            }
        }
    }

    public void setPremiumPromptOff(final UUID uuid, final boolean off) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO accounts (uuid, password_hash, premium_prompt_off, registered_at, "
                            + "source) VALUES (?, '', ?, ?, 'local') "
                            + "ON CONFLICT(uuid) DO UPDATE SET "
                            + "premium_prompt_off = excluded.premium_prompt_off")) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, off ? 1 : 0);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not save the premium prompt setting: " + exception.getMessage());
            }
        }
    }

    public void recordLogin(
            final UUID uuid, final String name, final String ip, final long now) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE accounts SET last_name = ?, last_login_at = ?, last_ip = ?, "
                            + "failed_attempts = 0, locked_until = 0 WHERE uuid = ?")) {
                statement.setString(1, name);
                statement.setLong(2, now);
                statement.setString(3, ip);
                statement.setString(4, uuid.toString());
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not record auth login: " + exception.getMessage());
            }
        }
    }

    public void clearFailures(final UUID uuid) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE accounts SET failed_attempts = 0, locked_until = 0 WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not clear auth failures: " + exception.getMessage());
            }
        }
    }

    /**
     * Reserves one password attempt for this account and address.
     *
     * <p>Both scopes are stored in SQLite, so quitting the server no longer resets the cooldown.
     * A failed attempt must not move the timestamp, otherwise retrying during the cooldown would
     * indefinitely extend it.
     *
     * @return true when an attempt may be made now
     */
    public boolean acquireAttempt(
            final UUID uuid,
            final String ip,
            final long now,
            final long cooldownMillis) {
        return acquireThrottle(
                "uuid:" + uuid,
                ip == null || ip.isBlank() ? null : "ip:" + ip,
                now,
                cooldownMillis);
    }

    /**
     * Reserves one registration attempt for this account and address.
     *
     * <p>Registration runs the same PBKDF2 cost as a login, so it needs its own throttle rather
     * than sharing the login scopes: a registration burst must not lock a player out of logging
     * in, and a login burst must not block a first-time registration. The scopes live in the same
     * table so a restart cannot reset them.
     *
     * @return true when an attempt may be made now
     */
    public boolean acquireRegisterAttempt(
            final UUID uuid,
            final String ip,
            final long now,
            final long cooldownMillis) {
        return acquireThrottle(
                "register-uuid:" + uuid,
                ip == null || ip.isBlank() ? null : "register-ip:" + ip,
                now,
                cooldownMillis);
    }

    private boolean acquireThrottle(
            final String accountScope,
            final String ipScope,
            final long now,
            final long cooldownMillis) {
        if (cooldownMillis <= 0) {
            return true;
        }

        synchronized (lock) {
            if (connection == null) {
                return false;
            }
            boolean autoCommit = true;
            try {
                autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                if (withinCooldown(accountScope, now, cooldownMillis)
                        || (ipScope != null
                                && withinCooldown(ipScope, now, cooldownMillis))) {
                    connection.rollback();
                    return false;
                }
                markAttempt(accountScope, now);
                if (ipScope != null) {
                    markAttempt(ipScope, now);
                }
                connection.commit();
                return true;
            } catch (final SQLException exception) {
                rollback(exception);
                warn.accept("Could not record the login attempt: " + exception.getMessage());
                return false;
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (final SQLException exception) {
                    warn.accept("Could not restore the auth database transaction mode: "
                            + exception.getMessage());
                }
            }
        }
    }

    private boolean withinCooldown(final String scope, final long now, final long cooldownMillis)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT last_attempt_at FROM login_throttle WHERE scope = ?")) {
            statement.setString(1, scope);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && now - result.getLong(1) < cooldownMillis;
            }
        }
    }

    private void markAttempt(final String scope, final long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO login_throttle (scope, last_attempt_at) VALUES (?, ?) "
                        + "ON CONFLICT(scope) DO UPDATE SET "
                        + "last_attempt_at = excluded.last_attempt_at")) {
            statement.setString(1, scope);
            statement.setLong(2, now);
            statement.executeUpdate();
        }
    }

    private void rollback(final SQLException cause) {
        try {
            connection.rollback();
        } catch (final SQLException exception) {
            cause.addSuppressed(exception);
        }
    }

    /**
     * Counts a failed attempt and locks the account once {@code maxAttempts} is reached.
     *
     * @return the new attempt count and the lock expiry, or an empty result when the account is
     *     missing
     */
    public Optional<Failure> recordFailure(
            final UUID uuid,
            final int maxAttempts,
            final long lockoutMillis,
            final long now) {
        synchronized (lock) {
            try {
                final int attempts;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT failed_attempts FROM accounts WHERE uuid = ?")) {
                    select.setString(1, uuid.toString());
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) {
                            return Optional.empty();
                        }
                        attempts = result.getInt(1) + 1;
                    }
                }

                final long lockedUntil = attempts >= maxAttempts ? now + lockoutMillis : 0L;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE accounts SET failed_attempts = ?, locked_until = ? WHERE uuid = ?")) {
                    update.setInt(1, attempts);
                    update.setLong(2, lockedUntil);
                    update.setString(3, uuid.toString());
                    update.executeUpdate();
                }
                return Optional.of(new Failure(attempts, lockedUntil));
            } catch (final SQLException exception) {
                warn.accept("Could not record auth failure: " + exception.getMessage());
                return Optional.empty();
            }
        }
    }

    public int countAccounts() {
        synchronized (lock) {
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
                return result.next() ? result.getInt(1) : 0;
            } catch (final SQLException exception) {
                warn.accept("Could not count auth accounts: " + exception.getMessage());
                return 0;
            }
        }
    }

    public boolean delete(final UUID uuid) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM accounts WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                return statement.executeUpdate() > 0;
            } catch (final SQLException exception) {
                warn.accept("Could not delete auth account: " + exception.getMessage());
                return false;
            }
        }
    }

    public void setPremium(final UUID uuid, final boolean premium, final long checkedAt) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO accounts "
                            + "(uuid, password_hash, premium, premium_checked_at, registered_at, source) "
                            + "VALUES (?, '', ?, ?, ?, 'premium') "
                            + "ON CONFLICT(uuid) DO UPDATE SET premium = excluded.premium, "
                            + "premium_checked_at = excluded.premium_checked_at")) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, premium ? 1 : 0);
                statement.setLong(3, checkedAt);
                statement.setLong(4, checkedAt);
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not update premium status: " + exception.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sessions

    public Optional<Session> session(final UUID uuid) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ip, expires_at FROM sessions WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.of(new Session(result.getString(1), result.getLong(2)))
                            : Optional.empty();
                }
            } catch (final SQLException exception) {
                warn.accept("Could not read auth session: " + exception.getMessage());
                return Optional.empty();
            }
        }
    }

    public void saveSession(final UUID uuid, final String ip, final long expiresAt) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sessions (uuid, ip, expires_at) VALUES (?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET ip = excluded.ip, "
                            + "expires_at = excluded.expires_at")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, ip);
                statement.setLong(3, expiresAt);
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not save auth session: " + exception.getMessage());
            }
        }
    }

    public void clearSession(final UUID uuid) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM sessions WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not clear auth session: " + exception.getMessage());
            }
        }
    }

    public void clearExpiredSessions(final long now) {
        synchronized (lock) {
            if (connection == null) {
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM sessions WHERE expires_at <= ?")) {
                statement.setLong(1, now);
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not clear expired auth sessions: " + exception.getMessage());
            }
        }
    }

    /**
     * Deletes throttle rows that can no longer affect an attempt.
     *
     * <p>The table has one row per account and one per address, so a long-lived server would
     * otherwise keep an entry for every pair that ever tried to log in. A row older than the
     * cooldown can never block a new attempt and is safe to drop.
     */
    public void clearStaleThrottles(final long cutoff) {
        synchronized (lock) {
            if (connection == null) {
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM login_throttle WHERE last_attempt_at < ?")) {
                statement.setLong(1, cutoff);
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not clear stale login throttles: " + exception.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pending locations

    /**
     * Stores the location a player should return to after logging in.
     *
     * <p>An existing row wins: a player who quits before logging in has already saved the real
     * location, and the auth-room position they were moved to must never overwrite it.
     */
    public void savePendingIfAbsent(final UUID uuid, final StoredLocation location) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO pending_locations "
                            + "(uuid, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, location.world());
                statement.setDouble(3, location.x());
                statement.setDouble(4, location.y());
                statement.setDouble(5, location.z());
                statement.setDouble(6, location.yaw());
                statement.setDouble(7, location.pitch());
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not save pending location: " + exception.getMessage());
            }
        }
    }

    /**
     * Overwrites the pending location. Used by {@code /logout} and admin unregister, where the
     * current position is newer than anything already stored.
     */
    public void savePending(final UUID uuid, final StoredLocation location) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pending_locations (uuid, world, x, y, z, yaw, pitch) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET world = excluded.world, "
                            + "x = excluded.x, y = excluded.y, z = excluded.z, "
                            + "yaw = excluded.yaw, pitch = excluded.pitch")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, location.world());
                statement.setDouble(3, location.x());
                statement.setDouble(4, location.y());
                statement.setDouble(5, location.z());
                statement.setDouble(6, location.yaw());
                statement.setDouble(7, location.pitch());
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not save pending location: " + exception.getMessage());
            }
        }
    }

    public Optional<StoredLocation> peekPending(final UUID uuid) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT world, x, y, z, yaw, pitch FROM pending_locations WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new StoredLocation(
                            result.getString(1),
                            result.getDouble(2),
                            result.getDouble(3),
                            result.getDouble(4),
                            (float) result.getDouble(5),
                            (float) result.getDouble(6)));
                }
            } catch (final SQLException exception) {
                warn.accept("Could not read pending location: " + exception.getMessage());
                return Optional.empty();
            }
        }
    }

    public void deletePending(final UUID uuid) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM pending_locations WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not clear pending location: " + exception.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Meta flags

    public Optional<String> meta(final String key) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM meta WHERE key = ?")) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.ofNullable(result.getString(1))
                            : Optional.empty();
                }
            } catch (final SQLException exception) {
                warn.accept("Could not read auth metadata: " + exception.getMessage());
                return Optional.empty();
            }
        }
    }

    public void setMeta(final String key, final String value) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO meta (key, value) VALUES (?, ?) "
                            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                statement.setString(1, key);
                statement.setString(2, value);
                statement.executeUpdate();
            } catch (final SQLException exception) {
                warn.accept("Could not save auth metadata: " + exception.getMessage());
            }
        }
    }

    public boolean flag(final String key) {
        return meta(key).map(Boolean::parseBoolean).orElse(false);
    }

    // ------------------------------------------------------------------------------------------
    // SimpleLogin import

    /**
     * Copies {@code uuid, password} rows from a SimpleLogin {@code passwords.db}.
     *
     * <p>The source database is opened read-only. Rows that already exist in the new database are
     * left alone, so this is safe to run on every start and never resets a changed password. All
     * rows are written in one transaction; a failure returns {@code -1} so the caller does not
     * mark the import as done.
     *
     * @return how many accounts were copied, or {@code -1} when the import failed
     */
    public int importSimpleLogin(final Path database, final long now) {
        if (database == null || !Files.isRegularFile(database)) {
            return 0;
        }

        final String url = "jdbc:sqlite:file:"
                + database.toAbsolutePath().toString().replace('\\', '/')
                + "?mode=ro";
        final List<LegacyAccount> accounts = new ArrayList<>();

        try (Connection legacy = DriverManager.getConnection(url);
                Statement statement = legacy.createStatement();
                ResultSet result = statement.executeQuery("SELECT uuid, password FROM users")) {
            while (result.next()) {
                final String rawUuid = result.getString(1);
                final String hash = result.getString(2);
                if (rawUuid == null || hash == null || !hash.startsWith("$2")) {
                    continue;
                }
                try {
                    accounts.add(new LegacyAccount(UUID.fromString(rawUuid), hash));
                } catch (final IllegalArgumentException exception) {
                    // A malformed row in the old database must not abort the other accounts.
                }
            }
        } catch (final SQLException exception) {
            warn.accept("Could not read SimpleLogin accounts from " + database + ": "
                    + exception.getMessage());
            return -1;
        }

        synchronized (lock) {
            if (connection == null) {
                return -1;
            }
            boolean autoCommit = true;
            try {
                autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                final int inserted;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR IGNORE INTO accounts "
                                + "(uuid, password_hash, source, registered_at) "
                                + "VALUES (?, ?, 'simplelogin', ?)")) {
                    for (final LegacyAccount account : accounts) {
                        statement.setString(1, account.uuid().toString());
                        statement.setString(2, account.hash());
                        statement.setLong(3, now);
                        statement.addBatch();
                    }
                    inserted = accounts.isEmpty() ? 0 : countInserted(statement.executeBatch());
                }
                connection.commit();
                return inserted;
            } catch (final SQLException exception) {
                rollback(exception);
                warn.accept("Could not import SimpleLogin accounts from " + database + ": "
                        + exception.getMessage());
                return -1;
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (final SQLException exception) {
                    warn.accept("Could not restore the auth database transaction mode: "
                            + exception.getMessage());
                }
            }
        }
    }

    private static int countInserted(final int[] results) {
        int inserted = 0;
        for (final int result : results) {
            if (result > 0 || result == Statement.SUCCESS_NO_INFO) {
                inserted++;
            }
        }
        return inserted;
    }

    // ------------------------------------------------------------------------------------------
    // Records

    private static Account readAccount(final ResultSet result) throws SQLException {
        return new Account(
                UUID.fromString(result.getString(1)),
                result.getString(2),
                result.getString(3),
                result.getInt(4) != 0,
                result.getLong(5),
                result.getInt(6) != 0,
                result.getString(7),
                result.getLong(8),
                result.getLong(9),
                result.getString(10),
                result.getInt(11),
                result.getLong(12));
    }

    public record Account(
            UUID uuid,
            String passwordHash,
            String lastName,
            boolean premium,
            long premiumCheckedAt,
            boolean premiumPromptOff,
            String source,
            long registeredAt,
            long lastLoginAt,
            String lastIp,
            int failedAttempts,
            long lockedUntil) {

        public boolean hasPassword() {
            return passwordHash != null && !passwordHash.isBlank();
        }
    }

    public record Session(String ip, long expiresAt) {}

    public record Failure(int attempts, long lockedUntil) {}

    private record LegacyAccount(UUID uuid, String hash) {}

    public record StoredLocation(
            String world, double x, double y, double z, float yaw, float pitch) {

        public static StoredLocation of(
                final String world,
                final double x,
                final double y,
                final double z,
                final float yaw,
                final float pitch) {
            return new StoredLocation(world, x, y, z, yaw, pitch);
        }
    }
}
