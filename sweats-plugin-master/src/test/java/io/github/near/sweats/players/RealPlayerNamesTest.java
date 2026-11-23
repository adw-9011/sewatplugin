package io.github.near.sweats.players;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RealPlayerNamesTest {

    @Test
    void acceptsAnySyntacticallyValidName() {
        // Fake-looking names are allowed here; the registry, not the name, decides who is real.
        for (final String name : List.of(
                "Bhosra", "A_kiyotaka_", "x3", "Near_123",
                "Shopkeeperkisuke", "shopkeeperkisuke", "Fake_Bhavya", "DAKSHBOT1")) {
            assertTrue(RealPlayerNames.valid(name), name);
        }
    }

    @Test
    void rejectsNamesThatCannotBeAccounts() {
        for (final String name : List.of(
                "103.173.20.139",
                "726c4b36-01fd-3f8f-b226-264b9f6619c5",
                "ab",
                "way_too_long_a_player_name",
                "name-with-dash",
                "name with space")) {
            assertFalse(RealPlayerNames.valid(name), name);
        }
    }
}
