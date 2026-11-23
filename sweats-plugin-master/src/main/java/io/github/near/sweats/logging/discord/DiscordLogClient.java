package io.github.near.sweats.logging.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bounded, single-worker Discord webhook sender for logging.
 *
 * <p>Features:
 * <ul>
 *   <li>FIFO queue preserves message ordering</li>
 *   <li>Rate limit handling (429) with Retry-After support</li>
 *   <li>Exponential backoff for 5xx errors</li>
 *   <li>4xx errors drop the message immediately</li>
 *   <li>Graceful shutdown drains queue</li>
 *   <li>All I/O off the main thread</li>
 * </ul>
 */
public final class DiscordLogClient implements AutoCloseable {

    private static final long CLOSE_WAIT_MILLIS = 5_000L;
    private static final long DROP_WARNING_INTERVAL_MILLIS = 10_000L;

    @FunctionalInterface
    public interface Transport {
        Response send(URI uri, String json, Duration timeout) throws Exception;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public record Response(int status, String body, long retryAfterMillis) {}

    private record Outbound(DiscordLogChannel channel, URI uri, String json) {}

    private final DiscordLogConfig config;
    private final Consumer<String> warn;
    private final Transport transport;
    private final Sleeper sleeper;
    private final BlockingQueue<Outbound> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastDropWarning = new AtomicLong();
    private final Thread worker;

    public DiscordLogClient(final DiscordLogConfig config, final Consumer<String> warn) {
        this(config, warn, new HttpTransport(config.timeoutSeconds()), Thread::sleep);
    }

    DiscordLogClient(
            final DiscordLogConfig config,
            final Consumer<String> warn,
            final Transport transport,
            final Sleeper sleeper) {
        this.config = config;
        this.warn = warn;
        this.transport = transport;
        this.sleeper = sleeper;
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.worker = new Thread(this::run, "Sweats-Discord-Logging");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Queues a pre-built JSON payload for sending.
     * Returns false if client is closed, channel not configured, or queue is full.
     */
    public boolean enqueue(final DiscordLogChannel channel, final String json) {
        if (!running.get() || json == null || json.isBlank()) {
            return false;
        }
        final URI webhook = config.getWebhook(channel);
        if (webhook == null) {
            return false;
        }
        if (!queue.offer(new Outbound(channel, webhook, json))) {
            dropped.incrementAndGet();
            warnDrop();
            return false;
        }
        return true;
    }

    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // Let worker drain queue, then stop
        try {
            worker.join(CLOSE_WAIT_MILLIS);
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(1_000L);
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.interrupt();
        }
    }

    private void run() {
        while (true) {
            final Outbound outbound;
            try {
                outbound = queue.poll(250L, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException interrupted) {
                if (!running.get() && queue.isEmpty()) {
                    return;
                }
                continue;
            }
            if (outbound == null) {
                if (!running.get() && queue.isEmpty()) {
                    return;
                }
                continue;
            }
            deliver(outbound);
        }
    }

    private void deliver(final Outbound outbound) {
        for (int attempt = 0; attempt <= config.retryAttempts(); attempt++) {
            try {
                final Response response = transport.send(outbound.uri(), outbound.json(), Duration.ofSeconds(config.timeoutSeconds()));
                if (response.status() >= 200 && response.status() < 300) {
                    return; // Success
                }
                if (response.status() == 429) {
                    if (attempt >= config.retryAttempts()) {
                        warn.accept("Discord rate-limited " + outbound.channel().configKey() + " channel; message dropped after " + attempt + " retries");
                        return;
                    }
                    sleep(response.retryAfterMillis() > 0L ? response.retryAfterMillis() : backoff(attempt));
                    continue;
                }
                if (response.status() >= 500 && response.status() < 600) {
                    if (attempt >= config.retryAttempts()) {
                        warn.accept("Discord returned HTTP " + response.status() + " for " + outbound.channel().configKey() + " channel; message dropped");
                        return;
                    }
                    sleep(backoff(attempt));
                    continue;
                }
                // 4xx - client error, don't retry
                warn.accept("Discord rejected " + outbound.channel().configKey() + " message (HTTP " + response.status() + ")");
                return;
            } catch (final InterruptedException interrupted) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (final Exception failure) {
                if (attempt >= config.retryAttempts()) {
                    warn.accept("Could not reach Discord for " + outbound.channel().configKey() + " channel: " + failure.getClass().getSimpleName());
                    return;
                }
                try {
                    sleep(backoff(attempt));
                } catch (final InterruptedException interrupted) {
                    if (!running.get()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private long backoff(final int attempt) {
        return Math.min(5_000L, config.retryDelayMillis() * (attempt + 1L));
    }

    private void sleep(final long millis) throws InterruptedException {
        if (millis > 0L) {
            sleeper.sleep(millis);
        } else {
            Thread.yield();
        }
    }

    private void warnDrop() {
        final long now = System.currentTimeMillis();
        final long last = lastDropWarning.get();
        if (now - last < DROP_WARNING_INTERVAL_MILLIS) {
            return;
        }
        if (lastDropWarning.compareAndSet(last, now)) {
            warn.accept("Discord logging queue is full; dropping messages (total dropped: " + dropped.get() + ")");
        }
    }

    private static final class HttpTransport implements Transport {

        private final Duration timeout;
        private final HttpClient client;

        private HttpTransport(final int timeoutSeconds) {
            this.timeout = Duration.ofSeconds(timeoutSeconds);
            this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        }

        @Override
        public Response send(final URI uri, final String json, final Duration requestTimeout) throws Exception {
            final HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(
                    response.statusCode(),
                    response.body(),
                    retryAfterMillis(response)
            );
        }

        private static long retryAfterMillis(final HttpResponse<String> response) {
            final Optional<String> header = response.headers().firstValue("Retry-After");
            if (header.isPresent()) {
                try {
                    return Math.max(0L, (long) (Double.parseDouble(header.get().trim()) * 1_000L));
                } catch (final NumberFormatException ignored) {
                    // Fall through to JSON body
                }
            }
            final String body = response.body();
            if (body == null || body.isBlank()) {
                return 0L;
            }
            try {
                final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("retry_after")) {
                    return Math.max(0L, (long) (json.get("retry_after").getAsDouble() * 1_000L));
                }
            } catch (final Exception ignored) {
            }
            return 0L;
        }
    }
}