package zelisline.ub.support.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuestSupportTokenServiceTest {

    private final GuestSupportTokenService tokens = new GuestSupportTokenService();

    @Test
    void mintProducesUniqueOpaqueTokens() {
        String a = tokens.mintToken();
        String b = tokens.mintToken();
        assertNotEquals(a, b);
        assertTrue(a.length() >= 40);
        // No padding or URL-hostile characters.
        assertFalse(a.contains("="));
    }

    @Test
    void hashMatchesOnlyExactToken() {
        String token = tokens.mintToken();
        String stored = tokens.hash(token);

        assertTrue(tokens.matches(token, stored));
        assertFalse(tokens.matches(token + "x", stored));
        assertFalse(tokens.matches(null, stored));
        assertFalse(tokens.matches("anything", null));
        assertFalse(tokens.matches("anything", ""));
    }

    @Test
    void storedHashIsNotTheTokenItself() {
        String token = tokens.mintToken();
        assertEquals(tokens.hash(token), tokens.hash(token));
        assertNotEquals(token, tokens.hash(token));
    }
}
