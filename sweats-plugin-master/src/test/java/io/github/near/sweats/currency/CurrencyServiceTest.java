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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrencyServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private ExecutorService executor;
    private CurrencyService service;

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        service = new CurrencyService(folder, id -> true, warnings::add, executor, Runnable::run);
        service.open();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void grantUpdatesTheCacheAndTheLedger() throws Exception {
        final UUID target = UUID.randomUUID();

        assertEquals(CurrencyService.GrantResult.SUCCESS,
                awaitGrant(target, CurrencyType.SWEAT_COINS, 25L, "rank purchase"));

        assertEquals(25L, service.balance(target, CurrencyType.SWEAT_COINS));
        assertTrue(service.loaded(target));

        service.close();
        final CurrencyStore reopened = new CurrencyStore(folder.resolve("currency.db"), warnings::add);
        reopened.open();
        try {
            assertEquals(25L, reopened.balance(target, CurrencyType.SWEAT_COINS));
            assertEquals(1, reopened.ledger(target).size());
            assertEquals("rank purchase", reopened.ledger(target).get(0).reason());
        } finally {
            reopened.close();
        }
        service = null;
    }

    @Test
    void rejectsAmountsOutsideTheConfiguredRange() {
        final UUID target = UUID.randomUUID();

        assertEquals(CurrencyService.GrantResult.INVALID_AMOUNT,
                grantNow(target, CurrencyType.SWEAT_COINS, 0L));
        assertEquals(CurrencyService.GrantResult.INVALID_AMOUNT,
                grantNow(target, CurrencyType.SWEAT_COINS, -3L));
        assertEquals(CurrencyService.GrantResult.INVALID_AMOUNT,
                grantNow(target, CurrencyType.SWEAT_COINS, service.maxGiveAmount() + 1L));
        assertEquals(0L, service.balance(target, CurrencyType.SWEAT_COINS));
    }

    @Test
    void concurrentGrantsDoNotLoseAChange() throws Exception {
        final UUID target = UUID.randomUUID();
        final int count = 60;
        final AtomicInteger successes = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(count);

        for (int index = 0; index < count; index++) {
            service.grant(null, target, CurrencyType.TRYHARD_SHARDS, 2L, "parallel", "test",
                    result -> {
                        if (result == CurrencyService.GrantResult.SUCCESS) {
                            successes.incrementAndGet();
                        }
                        done.countDown();
                    });
        }

        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertEquals(count, successes.get());
        assertEquals(count * 2L, service.balance(target, CurrencyType.TRYHARD_SHARDS));
    }

    @Test
    void reasonsAreSanitisedAndTruncated() {
        assertEquals("buy rank now", service.sanitizeReason("  buy \n rank \t now  "));
        assertEquals("unspecified", service.sanitizeReason("   "));
        assertEquals("unspecified", service.sanitizeReason(null));

        final String longReason = "x".repeat(200);
        assertEquals(80, service.sanitizeReason(longReason).length());
    }

    @Test
    void aClosedServiceRejectsFurtherGrants() {
        service.close();
        assertEquals(CurrencyService.GrantResult.REJECTED, grantNow(UUID.randomUUID(),
                CurrencyType.SWEAT_COINS, 1L));
        service = null;
    }

    @Test
    void anOverflowingGrantIsRejectedWithoutTouchingTheBalance() throws Exception {
        final UUID target = UUID.randomUUID();
        final CurrencyStore seed = new CurrencyStore(folder.resolve("currency.db"), warnings::add);
        seed.open();
        try {
            assertTrue(seed.add(target, CurrencyType.SWEAT_COINS, Long.MAX_VALUE - 1L,
                    null, "seed", "test", 1L).isPresent());
        } finally {
            seed.close();
        }

        assertEquals(CurrencyService.GrantResult.REJECTED,
                awaitGrant(target, CurrencyType.SWEAT_COINS, 2L, "overflow"));

        service.close();
        final CurrencyStore reopened = new CurrencyStore(folder.resolve("currency.db"), warnings::add);
        reopened.open();
        try {
            assertEquals(Long.MAX_VALUE - 1L, reopened.balance(target, CurrencyType.SWEAT_COINS));
        } finally {
            reopened.close();
        }
        service = null;
    }

    private CurrencyService.GrantResult awaitGrant(
            final UUID target, final CurrencyType type, final long amount, final String reason)
            throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<CurrencyService.GrantResult> result = new AtomicReference<>();
        service.grant(null, target, type, amount, reason, "test", value -> {
            result.set(value);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    private CurrencyService.GrantResult grantNow(
            final UUID target, final CurrencyType type, final long amount) {
        final AtomicReference<CurrencyService.GrantResult> result = new AtomicReference<>();
        service.grant(null, target, type, amount, "test", "test", result::set);
        return result.get();
    }
}
