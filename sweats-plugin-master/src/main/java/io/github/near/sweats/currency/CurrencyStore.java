package io.github.near.sweats.currency;

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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SQLite storage for balances and the append-only currency ledger.
 *
 * <p>One connection is shared and every operation is serialised on {@link #lock}. A balance change
 * and its ledger row are written in the same transaction, so a crash can never leave the ledger
 * disagreeing with the balance. All arithmetic is checked: an amount that would overflow a signed
 * 64-bit balance is rejected instead of silently becoming a floating-point value.
 */
public final class CurrencyStore implements AutoCloseable {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS currency_balances (
                uuid TEXT NOT NULL,
                currency TEXT NOT NULL,
                balance INTEGER NOT NULL,
                PRIMARY KEY (uuid, currency)
            );
            CREATE TABLE IF NOT EXISTS currency_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                issuer TEXT,
                target TEXT NOT NULL,
                currency TEXT NOT NULL,
                amount INTEGER NOT NULL,
                reason TEXT NOT NULL,
                source TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS currency_ledger_target
                ON currency_ledger (target, id);
            """;

    private final Path file;
    private final Consumer<String> warn;
    private final Object lock = new Object();

    private Connection connection;

    public CurrencyStore(final Path file, final Consumer<String> warn) {
        this.file = Objects.requireNonNull(file, "file");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    public void open() throws SQLException, IOException {
        final Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        synchronized (lock) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                for (final String ddl : SCHEMA.split(";")) {
                    if (!ddl.isBlank()) {
                        statement.execute(ddl);
                    }
                }
            }
            connection.setAutoCommit(true);
        }
    }

    /** Every known currency for one player. Missing rows are reported as zero. */
    public Map<CurrencyType, Long> balances(final UUID id) {
        final Map<CurrencyType, Long> result = new EnumMap<>(CurrencyType.class);
        for (final CurrencyType type : CurrencyType.values()) {
            result.put(type, 0L);
        }
        synchronized (lock) {
            try (PreparedStatement select = requireConnection().prepareStatement(
                    "SELECT currency, balance FROM currency_balances WHERE uuid = ?")) {
                select.setString(1, id.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        final Optional<CurrencyType> type = parseStored(rows.getString(1));
                        if (type.isPresent()) {
                            result.put(type.get(), rows.getLong(2));
                        }
                    }
                }
            } catch (final SQLException exception) {
                warn.accept("Could not read currency balances: " + exception.getMessage());
            }
        }
        return result;
    }

    public long balance(final UUID id, final CurrencyType currency) {
        synchronized (lock) {
            try (PreparedStatement select = requireConnection().prepareStatement(
                    "SELECT balance FROM currency_balances WHERE uuid = ? AND currency = ?")) {
                select.setString(1, id.toString());
                select.setString(2, currency.key());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            } catch (final SQLException exception) {
                warn.accept("Could not read a currency balance: " + exception.getMessage());
                return 0L;
            }
        }
    }

    /**
     * Adds {@code amount} to the target and appends one ledger row atomically.
     *
     * @return the new balance, or empty when the amount is not positive, the balance would
     *         overflow, or the database could not be written
     */
    public OptionalLong add(
            final UUID target,
            final CurrencyType currency,
            final long amount,
            final UUID issuer,
            final String reason,
            final String source,
            final long timestamp) {
        if (amount <= 0) {
            return OptionalLong.empty();
        }
        synchronized (lock) {
            final Connection active = requireConnection();
            try {
                active.setAutoCommit(false);
                final long current = currentBalance(active, target, currency);
                final long updated;
                try {
                    updated = Math.addExact(current, amount);
                } catch (final ArithmeticException overflow) {
                    active.rollback();
                    return OptionalLong.empty();
                }

                try (PreparedStatement upsert = active.prepareStatement("""
                        INSERT INTO currency_balances (uuid, currency, balance)
                        VALUES (?, ?, ?)
                        ON CONFLICT(uuid, currency) DO UPDATE SET balance = excluded.balance
                        """)) {
                    upsert.setString(1, target.toString());
                    upsert.setString(2, currency.key());
                    upsert.setLong(3, updated);
                    upsert.executeUpdate();
                }

                try (PreparedStatement insert = active.prepareStatement("""
                        INSERT INTO currency_ledger
                            (issuer, target, currency, amount, reason, source, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    if (issuer == null) {
                        insert.setNull(1, java.sql.Types.VARCHAR);
                    } else {
                        insert.setString(1, issuer.toString());
                    }
                    insert.setString(2, target.toString());
                    insert.setString(3, currency.key());
                    insert.setLong(4, amount);
                    insert.setString(5, reason);
                    insert.setString(6, source);
                    insert.setLong(7, timestamp);
                    insert.executeUpdate();
                }

                active.commit();
                return OptionalLong.of(updated);
            } catch (final SQLException exception) {
                try {
                    active.rollback();
                } catch (final SQLException rollback) {
                    exception.addSuppressed(rollback);
                }
                warn.accept("Could not update a currency balance: " + exception.getMessage());
                return OptionalLong.empty();
            } finally {
                try {
                    active.setAutoCommit(true);
                } catch (final SQLException ignored) {
                    // The next operation will report the broken connection.
                }
            }
        }
    }

    /** Ledger rows for one target, oldest first. Mainly for audits and tests. */
    public List<LedgerEntry> ledger(final UUID target) {
        final List<LedgerEntry> entries = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement select = requireConnection().prepareStatement("""
                    SELECT id, issuer, target, currency, amount, reason, source, created_at
                    FROM currency_ledger WHERE target = ? ORDER BY id
                    """)) {
                select.setString(1, target.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        entries.add(new LedgerEntry(
                                rows.getLong(1),
                                parseUuid(rows.getString(2)),
                                UUID.fromString(rows.getString(3)),
                                parseStored(rows.getString(4)).orElse(CurrencyType.SWEAT_COINS),
                                rows.getLong(5),
                                rows.getString(6),
                                rows.getString(7),
                                rows.getLong(8)));
                    }
                }
            } catch (final SQLException exception) {
                warn.accept("Could not read the currency ledger: " + exception.getMessage());
            }
        }
        return entries;
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
                warn.accept("Could not close the currency database: " + exception.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    private Connection requireConnection() {
        if (connection == null) {
            throw new IllegalStateException("Currency database is not open");
        }
        return connection;
    }

    private static long currentBalance(
            final Connection active, final UUID target, final CurrencyType currency)
            throws SQLException {
        try (PreparedStatement select = active.prepareStatement(
                "SELECT balance FROM currency_balances WHERE uuid = ? AND currency = ?")) {
            select.setString(1, target.toString());
            select.setString(2, currency.key());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private static Optional<CurrencyType> parseStored(final String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        for (final CurrencyType type : CurrencyType.values()) {
            if (type.key().equalsIgnoreCase(stored)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private static UUID parseUuid(final String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (final IllegalArgumentException invalid) {
            return null;
        }
    }

    public record LedgerEntry(
            long id,
            UUID issuer,
            UUID target,
            CurrencyType currency,
            long amount,
            String reason,
            String source,
            long timestamp) {}
}
