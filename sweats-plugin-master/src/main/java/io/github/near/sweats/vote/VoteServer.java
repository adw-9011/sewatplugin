package io.github.near.sweats.vote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Small standalone Votifier receiver.
 *
 * <p>Speaks both the legacy protocol used by most server lists (including Minecraft.Buzz, a
 * 256-byte RSA block) and the token-authenticated v2 protocol. It deliberately has no Bukkit
 * dependency beyond the logger so the wire format can be tested on its own.
 */
public final class VoteServer {

    private static final int MAGIC_V2 = 0x733A;
    private static final int V1_BLOCK_SIZE = 256;
    private static final int SOCKET_TIMEOUT_MS = 15_000;

    private final String host;
    private final int port;
    private final String token;
    private final PrivateKey privateKey;
    private final boolean allowV1;
    private final Consumer<Vote> handler;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();

    private ServerSocket serverSocket;
    private ExecutorService workers;
    private Thread acceptor;
    private volatile boolean running;

    public VoteServer(
            final String host,
            final int port,
            final String token,
            final PrivateKey privateKey,
            final boolean allowV1,
            final Consumer<Vote> handler,
            final Logger logger) {
        this.host = host;
        this.port = port;
        this.token = token == null ? "" : token;
        this.privateKey = privateKey;
        this.allowV1 = allowV1 && privateKey != null;
        this.handler = handler;
        this.logger = logger;
    }

    /** Binds the port and starts accepting votes. */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        final ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(host, port));
        this.serverSocket = socket;
        this.running = true;

        this.workers = Executors.newCachedThreadPool(task -> {
            final Thread thread = new Thread(task, "Sweats-votifier-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.acceptor = new Thread(this::acceptLoop, "Sweats-votifier-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        logger.info("Votifier listener bound to " + host + ":" + port
                + " (v1 " + (allowV1 ? "enabled" : "disabled") + ", v2 "
                + (token.isBlank() ? "disabled" : "enabled") + ")");
    }

    /** Stops accepting votes and releases the port. */
    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (final IOException ignored) {
                // shutting down
            }
            serverSocket = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        if (acceptor != null) {
            acceptor.interrupt();
            acceptor = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return port;
    }

    /** Actual port in use, useful when bound to port {@code 0} in tests. */
    public int boundPort() {
        final ServerSocket socket = serverSocket;
        return socket == null ? port : socket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            final ServerSocket socket = serverSocket;
            if (socket == null) {
                return;
            }
            try {
                final Socket client = socket.accept();
                if (running && workers != null) {
                    workers.execute(() -> handle(client));
                } else {
                    client.close();
                }
            } catch (final SocketException exception) {
                if (running) {
                    logger.log(Level.WARNING, "Votifier accept failed: " + exception.getMessage());
                }
            } catch (final IOException exception) {
                if (running) {
                    logger.log(Level.WARNING, "Votifier accept failed: " + exception.getMessage());
                }
            }
        }
    }

    private void handle(final Socket client) {
        final String peer = String.valueOf(client.getRemoteSocketAddress());
        try (client) {
            client.setSoTimeout(SOCKET_TIMEOUT_MS);
            final DataInputStream in = new DataInputStream(client.getInputStream());
            final OutputStream out = client.getOutputStream();

            // Both protocol versions expect a greeting before the vote is sent.
            final String challenge = challenge();
            out.write(("VOTIFIER 2 " + challenge + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            final byte[] head = new byte[2];
            in.readFully(head);

            final Vote vote;
            if (isV2(head)) {
                final byte[] lengthBytes = new byte[2];
                in.readFully(lengthBytes);
                final int length = ((lengthBytes[0] & 0xFF) << 8) | (lengthBytes[1] & 0xFF);
                if (length == 0 || length > 65535) {
                    throw new IOException("invalid v2 payload length " + length);
                }
                final byte[] body = new byte[length];
                in.readFully(body);

                vote = decodeV2(body, challenge);
                out.write("{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } else {
                if (!allowV1) {
                    logger.warning("Rejected a legacy Votifier vote from " + peer
                            + " (protocol v1 is disabled or no RSA key is loaded)");
                    return;
                }
                final byte[] block = new byte[V1_BLOCK_SIZE];
                System.arraycopy(head, 0, block, 0, head.length);
                in.readFully(block, head.length, V1_BLOCK_SIZE - head.length);
                vote = decodeV1(block);
            }

            if (vote != null && running) {
                handler.accept(vote);
            }
        } catch (final SocketTimeoutException exception) {
            logger.fine("Votifier connection from " + peer + " timed out");
        } catch (final Exception exception) {
            if (running) {
                logger.warning("Could not read vote from " + peer + ": " + exception.getMessage());
            }
        }
    }

    private Vote decodeV1(final byte[] block) throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        final String plain = new String(cipher.doFinal(block), StandardCharsets.UTF_8);
        final String[] fields = plain.split("\n", -1);
        for (int index = 0; index < fields.length; index++) {
            fields[index] = fields[index].trim();
        }
        if (fields.length < 5 || !"VOTE".equals(fields[0])) {
            throw new GeneralSecurityException("invalid protocol v1 vote record");
        }
        return new Vote(
                fields[1], fields[2], fields[3], parseTimestamp(fields[4]), 1, "");
    }

    private Vote decodeV2(final byte[] body, final String challenge)
            throws GeneralSecurityException {
        final JsonObject wrapper =
                JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        final String payload = wrapper.get("payload").getAsString();
        final byte[] signature = signature(wrapper.get("signature"));

        final JsonObject inner = JsonParser.parseString(payload).getAsJsonObject();
        final String receivedChallenge = string(inner, "challenge");
        if (!challenge.equals(receivedChallenge)) {
            throw new GeneralSecurityException("protocol v2 challenge mismatch");
        }
        if (token.isBlank()) {
            throw new GeneralSecurityException("no protocol v2 token configured");
        }

        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        final byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, signature)) {
            throw new GeneralSecurityException("protocol v2 signature mismatch");
        }

        return new Vote(
                string(inner, "serviceName"),
                string(inner, "username"),
                string(inner, "address"),
                parseLong(string(inner, "timestamp")),
                2,
                string(inner, "uuid"));
    }

    private static byte[] signature(final JsonElement element) throws GeneralSecurityException {
        if (element == null) {
            throw new GeneralSecurityException("missing protocol v2 signature");
        }
        if (element.isJsonArray()) {
            final byte[] bytes = new byte[element.getAsJsonArray().size()];
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = element.getAsJsonArray().get(index).getAsByte();
            }
            return bytes;
        }
        return Base64.getDecoder().decode(element.getAsString());
    }

    private static boolean isV2(final byte[] head) {
        return head.length >= 2 && (head[0] & 0xFF) == 0x73 && (head[1] & 0xFF) == 0x3A;
    }

    private static String string(final JsonObject object, final String key) {
        final JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long parseTimestamp(final String value) {
        final long parsed = parseLong(value);
        // Votifier v1 reports seconds; normalise to milliseconds for logs and deduplication.
        return parsed > 0 && parsed < 100_000_000_000L ? parsed * 1000L : parsed;
    }

    private static long parseLong(final String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (final RuntimeException exception) {
            return System.currentTimeMillis();
        }
    }

    private String challenge() {
        final byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
