package io.github.near.sweats.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A salted comparison digest for passwords held by a multi-message prompt.
 *
 * <p>The login and registration prompts need to compare two chat messages, but keeping the first
 * password in memory as plaintext leaves it readable for as long as the prompt exists. This digest
 * only proves that the second message is the same password; the real password hash is still
 * produced by {@link PasswordHasher} when the account is saved.
 */
final class PromptDigest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;

    private PromptDigest() {}

    static String of(final String password) {
        final byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt)
                + ":"
                + Base64.getEncoder().encodeToString(hash(salt, password));
    }

    static boolean matches(final String password, final String stored) {
        if (password == null || stored == null) {
            return false;
        }
        final int separator = stored.indexOf(':');
        if (separator <= 0 || separator == stored.length() - 1) {
            return false;
        }
        try {
            final byte[] salt = Base64.getDecoder().decode(stored.substring(0, separator));
            final byte[] expected = Base64.getDecoder().decode(stored.substring(separator + 1));
            return MessageDigest.isEqual(expected, hash(salt, password));
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] hash(final byte[] salt, final String password) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
