package io.github.near.sweats.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

class PasswordHasherTest {

    @Test
    void aHashedPasswordVerifiesOnlyWithTheSamePassword() {
        final String hash = PasswordHasher.hash("correct horse battery staple");

        assertTrue(PasswordHasher.verify("correct horse battery staple", hash));
        assertFalse(PasswordHasher.verify("Correct horse battery staple", hash));
        assertFalse(PasswordHasher.verify("", hash));
        assertFalse(PasswordHasher.verify(null, hash));
        assertTrue(hash.startsWith("pbkdf2$sha256$"));
        assertFalse(PasswordHasher.needsRehash(hash));
    }

    @Test
    void everyHashUsesAFreshSalt() {
        assertNotEquals(PasswordHasher.hash("same password"), PasswordHasher.hash("same password"));
    }

    @Test
    void legacySimpleLoginBcryptHashesStillWork() {
        final String legacy = BCrypt.hashpw("old-simplelogin-password", BCrypt.gensalt(12));

        assertTrue(PasswordHasher.verify("old-simplelogin-password", legacy));
        assertFalse(PasswordHasher.verify("wrong", legacy));
        assertTrue(PasswordHasher.needsRehash(legacy));
    }

    @Test
    void damagedAndUnknownHashesFailClosed() {
        assertFalse(PasswordHasher.verify("x", null));
        assertFalse(PasswordHasher.verify("x", ""));
        assertFalse(PasswordHasher.verify("x", "plaintext"));
        assertFalse(PasswordHasher.verify("x", "pbkdf2$sha256$notanumber$c2FsdA==$aGFzaA=="));
        assertFalse(PasswordHasher.verify("x", "pbkdf2$sha256$210000$@@@$@@@"));
        assertFalse(PasswordHasher.verify("x", "$2a$12$not-a-real-bcrypt-hash"));
    }
}
