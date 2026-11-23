package io.github.near.sweats.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptDigestTest {

    @Test
    void matchesOnlyTheSamePassword() {
        final String digest = PromptDigest.of("correct horse battery staple");

        assertTrue(PromptDigest.matches("correct horse battery staple", digest));
        assertFalse(PromptDigest.matches("Correct horse battery staple", digest));
        assertFalse(PromptDigest.matches("", digest));
        assertFalse(PromptDigest.matches(null, digest));
    }

    @Test
    void everyDigestUsesAFreshSaltAndDoesNotContainThePassword() {
        final String first = PromptDigest.of("same password");
        final String second = PromptDigest.of("same password");

        assertNotEquals(first, second);
        assertFalse(first.contains("same password"));
    }

    @Test
    void malformedDigestsNeverMatch() {
        assertFalse(PromptDigest.matches("password", null));
        assertFalse(PromptDigest.matches("password", ""));
        assertFalse(PromptDigest.matches("password", "no-separator"));
        assertFalse(PromptDigest.matches("password", "not-base64:not-base64"));
        assertFalse(PromptDigest.matches("password", ":"));
        assertFalse(PromptDigest.matches("password", "c2FsdA=="));
    }
}
