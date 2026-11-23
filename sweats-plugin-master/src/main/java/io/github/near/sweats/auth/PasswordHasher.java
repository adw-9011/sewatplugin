package io.github.near.sweats.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Password hashing for new accounts, plus verification of the BCrypt hashes SimpleLogin left
 * behind.
 *
 * <p>New passwords use PBKDF2-HMAC-SHA256 with a per-password salt. Existing {@code $2a$} hashes
 * are still accepted once and then replaced with a PBKDF2 hash by {@link AuthService}.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2$sha256$";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    /** Hashes a password into {@code pbkdf2$sha256$iterations$salt$hash}. */
    public static String hash(final String password) {
        final byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        final byte[] key = derive(password, salt, ITERATIONS, KEY_BITS);
        return PREFIX
                + ITERATIONS
                + "$"
                + Base64.getEncoder().encodeToString(salt)
                + "$"
                + Base64.getEncoder().encodeToString(key);
    }

    /** Constant-time verification of a stored PBKDF2 or legacy BCrypt hash. */
    public static boolean verify(final String password, final String stored) {
        if (password == null || stored == null || stored.isEmpty()) {
            return false;
        }

        if (stored.startsWith(PREFIX)) {
            return verifyPbkdf2(password, stored);
        }
        if (stored.startsWith("$2")) {
            return verifyBcrypt(password, stored);
        }
        return false;
    }

    /** True when the stored hash should be upgraded after a successful login. */
    public static boolean needsRehash(final String stored) {
        return stored == null || !stored.startsWith(PREFIX + ITERATIONS + "$");
    }

    private static boolean verifyPbkdf2(final String password, final String stored) {
        final String[] parts = stored.split("\\$");
        if (parts.length != 5 || !"pbkdf2".equals(parts[0]) || !"sha256".equals(parts[1])) {
            return false;
        }

        try {
            final int iterations = Integer.parseInt(parts[2]);
            if (iterations < 10_000 || iterations > 5_000_000) {
                return false;
            }
            final byte[] salt = Base64.getDecoder().decode(parts[3]);
            final byte[] expected = Base64.getDecoder().decode(parts[4]);
            if (expected.length == 0 || salt.length < 8) {
                return false;
            }
            final byte[] actual = derive(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean verifyBcrypt(final String password, final String stored) {
        String hash = stored;
        // jBCrypt understands the $2a$ format; $2y$/$2b$ are the same algorithm.
        if (hash.startsWith("$2y$") || hash.startsWith("$2b$")) {
            hash = "$2a$" + hash.substring(4);
        }
        try {
            return BCrypt.checkpw(password, hash);
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(
            final String password, final byte[] salt, final int iterations, final int bits) {
        final PBEKeySpec spec =
                new PBEKeySpec(password.toCharArray(), salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (final NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("This Java runtime does not support " + ALGORITHM, exception);
        } finally {
            spec.clearPassword();
        }
    }
}
