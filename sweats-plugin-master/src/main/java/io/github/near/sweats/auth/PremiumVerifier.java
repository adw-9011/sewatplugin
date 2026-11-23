package io.github.near.sweats.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a name/UUID pair against Mojang's public profile API.
 *
 * <p>Only UUID version 4 can be a Mojang UUID; offline-mode servers generate version 3 UUIDs from
 * the player name, so those are rejected without a network call.
 */
public final class PremiumVerifier {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final String USER_AGENT = "Sweats/1.0 (Minecraft server login check)";
    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    private final HttpClient client;

    public PremiumVerifier() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    PremiumVerifier(final HttpClient client) {
        this.client = client;
    }

    public CompletableFuture<Result> verify(final String name, final UUID uuid) {
        if (name == null || name.isBlank() || uuid == null || uuid.version() != 4) {
            return CompletableFuture.completedFuture(Result.MISMATCH);
        }

        final URI uri = URI.create("https://api.mojang.com/users/profiles/minecraft/"
                + URLEncoder.encode(name, StandardCharsets.UTF_8));
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return client
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> error == null
                        ? interpret(name, uuid, response.statusCode(), response.body())
                        : Result.UNKNOWN);
    }

    /** Turns an HTTP response into a decision; package-private so it can be tested offline. */
    static Result interpret(
            final String name, final UUID uuid, final int status, final String body) {
        if (uuid == null || uuid.version() != 4) {
            return Result.MISMATCH;
        }
        if (status == 204 || status == 404) {
            return Result.MISMATCH;
        }
        if (status != 200 || body == null) {
            return Result.UNKNOWN;
        }

        final Matcher matcher = ID.matcher(body);
        if (!matcher.find()) {
            return Result.UNKNOWN;
        }
        final String expected = uuid.toString().replace("-", "");
        return expected.equalsIgnoreCase(matcher.group(1))
                ? Result.VERIFIED
                : Result.MISMATCH;
    }

    public enum Result {
        /** Mojang confirms this name belongs to this UUID. */
        VERIFIED,
        /** Mojang was reachable and says the pair does not match. */
        MISMATCH,
        /** Mojang could not be reached, or returned an unexpected response. */
        UNKNOWN
    }
}
