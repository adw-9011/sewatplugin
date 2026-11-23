package io.github.near.sweats.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PremiumVerifierTest {

    private static final UUID PREMIUM = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void aMatchingMojangProfileIsVerified() {
        final String body = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";

        assertEquals(PremiumVerifier.Result.VERIFIED,
                PremiumVerifier.interpret("Notch", PREMIUM, 200, body));
    }

    @Test
    void aDifferentUuidIsAMismatch() {
        final String body = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";

        assertEquals(PremiumVerifier.Result.MISMATCH,
                PremiumVerifier.interpret("Notch", UUID.randomUUID(), 200, body));
    }

    @Test
    void unknownNamesAreAMismatchAndServerErrorsAreUnknown() {
        assertEquals(PremiumVerifier.Result.MISMATCH,
                PremiumVerifier.interpret("Notch", PREMIUM, 204, ""));
        assertEquals(PremiumVerifier.Result.MISMATCH,
                PremiumVerifier.interpret("Notch", PREMIUM, 404, ""));
        assertEquals(PremiumVerifier.Result.UNKNOWN,
                PremiumVerifier.interpret("Notch", PREMIUM, 500, "oops"));
        assertEquals(PremiumVerifier.Result.UNKNOWN,
                PremiumVerifier.interpret("Notch", PREMIUM, 200, "not json"));
    }

    @Test
    void offlineModeUuidsAreRejectedWithoutANetworkCall() {
        final UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes());

        assertEquals(PremiumVerifier.Result.MISMATCH,
                PremiumVerifier.interpret("Notch", offline, 200,
                        "{\"id\":\"069a79f444e94726a5befca90e38aaf5\"}"));
    }
}
