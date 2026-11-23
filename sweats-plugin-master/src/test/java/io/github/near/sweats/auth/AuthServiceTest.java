package io.github.near.sweats.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    @Test
    void sessionsRestoreOnlyForForwardedUuidsOnTheSameAddress() {
        final UUID forwarded = UUID.randomUUID();
        final UUID offline = UUID.nameUUIDFromBytes(
                "OfflinePlayer:Alex".getBytes(StandardCharsets.UTF_8));

        assertTrue(AuthService.canRestoreSession(forwarded, true, true, 2_000L, 1_000L));
        assertFalse(AuthService.canRestoreSession(offline, true, true, 2_000L, 1_000L));
        assertFalse(AuthService.canRestoreSession(forwarded, false, true, 2_000L, 1_000L));
        assertFalse(AuthService.canRestoreSession(forwarded, true, false, 2_000L, 1_000L));
        assertFalse(AuthService.canRestoreSession(forwarded, true, true, 1_000L, 1_000L));
        assertFalse(AuthService.canRestoreSession(null, true, true, 2_000L, 1_000L));
    }

    @Test
    void theAuthExecutorRejectsWorkWhenItsBoundedQueueIsFull() throws Exception {
        final ThreadPoolExecutor executor = AuthService.authExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                try {
                    release.await();
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            for (int index = 0; index < AuthService.EXECUTOR_QUEUE_CAPACITY; index++) {
                executor.execute(() -> {});
            }

            assertThrows(
                    RejectedExecutionException.class,
                    () -> executor.execute(() -> {}));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }
}
