package io.github.near.sweats.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrencyStoreTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private CurrencyStore store;

    @BeforeEach
    void open() throws Exception {
        store = new CurrencyStore(folder.resolve("currency.db"), warnings::add);
        store.open();
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void balanceAndLedgerChangeTogether() {
        final UUID target = UUID.randomUUID();
        final UUID issuer = UUID.randomUUID();

        assertTrue(store.add(target, CurrencyType.SWEAT_COINS, 25, issuer,
                "test grant", "command", 1_700_000_000_000L).isPresent());

        assertEquals(25L, store.balance(target, CurrencyType.SWEAT_COINS));
        assertEquals(0L, store.balance(target, CurrencyType.TRYHARD_SHARDS));

        final List<CurrencyStore.LedgerEntry> ledger = store.ledger(target);
        assertEquals(1, ledger.size());
        final CurrencyStore.LedgerEntry entry = ledger.get(0);
        assertEquals(issuer, entry.issuer());
        assertEquals(target, entry.target());
        assertEquals(CurrencyType.SWEAT_COINS, entry.currency());
        assertEquals(25L, entry.amount());
        assertEquals("test grant", entry.reason());
        assertEquals("command", entry.source());
        assertEquals(1_700_000_000_000L, entry.timestamp());
    }

    @Test
    void currenciesAreIndependent() {
        final UUID target = UUID.randomUUID();

        store.add(target, CurrencyType.SWEAT_COINS, 10, null, "a", "test", 1L);
        store.add(target, CurrencyType.TRYHARD_SHARDS, 4, null, "b", "test", 2L);

        assertEquals(10L, store.balance(target, CurrencyType.SWEAT_COINS));
        assertEquals(4L, store.balance(target, CurrencyType.TRYHARD_SHARDS));
        assertEquals(2, store.ledger(target).size());
    }

    @Test
    void overflowIsRejectedAndDoesNotChangeTheBalance() {
        final UUID target = UUID.randomUUID();
        assertTrue(store.add(target, CurrencyType.SWEAT_COINS,
                Long.MAX_VALUE - 10L, null, "seed", "test", 1L).isPresent());

        assertFalse(store.add(target, CurrencyType.SWEAT_COINS,
                11L, null, "overflow", "test", 2L).isPresent());

        assertEquals(Long.MAX_VALUE - 10L, store.balance(target, CurrencyType.SWEAT_COINS));
        assertEquals(1, store.ledger(target).size());
    }

    @Test
    void nonPositiveAmountsAreIgnored() {
        final UUID target = UUID.randomUUID();

        assertFalse(store.add(target, CurrencyType.SWEAT_COINS, 0L, null, "x", "test", 1L).isPresent());
        assertFalse(store.add(target, CurrencyType.SWEAT_COINS, -5L, null, "x", "test", 1L).isPresent());
        assertEquals(0L, store.balance(target, CurrencyType.SWEAT_COINS));
        assertTrue(store.ledger(target).isEmpty());
    }

    @Test
    void concurrentDepositsAreSerialisedWithoutLosingAChange() throws Exception {
        final UUID target = UUID.randomUUID();
        final int threads = 8;
        final int perThread = 40;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger failures = new AtomicInteger();

        for (int thread = 0; thread < threads; thread++) {
            pool.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int deposit = 0; deposit < perThread; deposit++) {
                        if (store.add(target, CurrencyType.SWEAT_COINS, 3L,
                                null, "concurrent", "test", deposit).isEmpty()) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(0, failures.get());
        assertEquals((long) threads * perThread * 3L,
                store.balance(target, CurrencyType.SWEAT_COINS));
        assertEquals(threads * perThread, store.ledger(target).size());
    }

    @Test
    void reopeningKeepsTheBalance() throws Exception {
        final UUID target = UUID.randomUUID();
        store.add(target, CurrencyType.TRYHARD_SHARDS, 9, null, "persist", "test", 1L);
        store.close();

        store = new CurrencyStore(folder.resolve("currency.db"), warnings::add);
        store.open();
        assertEquals(9L, store.balance(target, CurrencyType.TRYHARD_SHARDS));
    }
}
