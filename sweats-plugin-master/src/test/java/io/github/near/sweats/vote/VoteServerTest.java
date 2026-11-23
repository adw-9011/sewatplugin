package io.github.near.sweats.vote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Exercises the wire protocol without a running Minecraft server. */
class VoteServerTest {

    private static final Logger LOG = Logger.getLogger("VoteServerTest");

    @Test
    void decodesLegacyProtocolVote() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair keys = generator.generateKeyPair();

        final BlockingQueue<Vote> votes = new ArrayBlockingQueue<>(4);
        final VoteServer server = new VoteServer("127.0.0.1", 0, "", keys.getPrivate(), true,
                votes::add, LOG);
        server.start();
        try {
            final String plain = "VOTE\nMinecraft.Buzz\nBhosta\n2409:4091::1\n1794942327\n";
            final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.getPublic());
            final byte[] block = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            assertEquals(256, block.length);

            try (final Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 2000);
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                assertTrue(reader.readLine().startsWith("VOTIFIER 2 "));
                socket.getOutputStream().write(block);
                socket.getOutputStream().flush();
            }

            final Vote vote = votes.poll(5, TimeUnit.SECONDS);
            assertNotNull(vote);
            assertEquals("Minecraft.Buzz", vote.service());
            assertEquals("Bhosta", vote.username());
            assertEquals("2409:4091::1", vote.address());
            assertEquals(1794942327L * 1000L, vote.timestamp());
            assertEquals(1, vote.protocol());
        } finally {
            server.stop();
        }
    }

    @Test
    void decodesTokenAuthenticatedVote() throws Exception {
        final String token = "nvcpg9vjtnjloo4c1cdddk2295";
        final BlockingQueue<Vote> votes = new ArrayBlockingQueue<>(4);
        final VoteServer server = new VoteServer("127.0.0.1", 0, token, null, false,
                votes::add, LOG);
        server.start();
        try {
            try (final Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 2000);
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                final String greeting = reader.readLine();
                assertTrue(greeting.startsWith("VOTIFIER 2 "));
                final String challenge = greeting.substring("VOTIFIER 2 ".length());

                final JsonObject payload = new JsonObject();
                payload.addProperty("serviceName", "Minecraft.Buzz");
                payload.addProperty("username", "Pekadeco");
                payload.addProperty("address", "1.2.3.4");
                payload.addProperty("timestamp", 1794942653000L);
                payload.addProperty("challenge", challenge);
                payload.addProperty("uuid", "652a2bc4-e8cd-405d-b7b6-98156ee2dc09");

                final JsonObject wrapper = new JsonObject();
                wrapper.addProperty("payload", payload.toString());
                final Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                wrapper.addProperty("signature",
                        Base64.getEncoder().encodeToString(
                                mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8))));

                final byte[] message = wrapper.toString().getBytes(StandardCharsets.UTF_8);
                final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeShort(0x733A);
                out.writeShort(message.length);
                out.write(message);
                out.flush();

                assertEquals("{\"status\":\"ok\"}", reader.readLine());
            }

            final Vote vote = votes.poll(5, TimeUnit.SECONDS);
            assertNotNull(vote);
            assertEquals("Minecraft.Buzz", vote.service());
            assertEquals("Pekadeco", vote.username());
            assertEquals(1794942653000L, vote.timestamp());
            assertEquals(2, vote.protocol());
            assertEquals("652a2bc4-e8cd-405d-b7b6-98156ee2dc09", vote.uuid());
        } finally {
            server.stop();
        }
    }
}
