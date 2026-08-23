package zelisline.ub.storefront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebOrderCodesTest {

    @Test
    void code_derivesLast8CompactCharsUppercased() {
        String id = "9e2e4d29-0f2c-4f7a-9a4b-2d3c5f6a7b8c";
        // compact: 9e2e4d290f2c4f7a9a4b2d3c5f6a7b8c — last 8: 5f6a7b8c
        assertEquals("5F6A7B8C", WebOrderCodes.code(id));
    }

    @Test
    void code_blankInput() {
        assertEquals("", WebOrderCodes.code(null));
        assertEquals("", WebOrderCodes.code("   "));
    }

    @Test
    void matches_caseInsensitiveAndIgnoresSuffixNoise() {
        String id = "9e2e4d29-0f2c-4f7a-9a4b-2d3c5f6a7b8c";
        assertTrue(WebOrderCodes.matches("5f6a7b8c", id));
        assertTrue(WebOrderCodes.matches("5F6A-7B8C", id));
        assertFalse(WebOrderCodes.matches("00000000", id));
        assertFalse(WebOrderCodes.matches(null, id));
        assertFalse(WebOrderCodes.matches("5F6A7B8C", null));
    }
}
