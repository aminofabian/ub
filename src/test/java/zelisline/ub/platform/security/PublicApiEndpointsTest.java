package zelisline.ub.platform.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PublicApiEndpointsTest {

    @Test
    void publicStorefrontPathsMatch() {
        assertTrue(PublicApiEndpoints.matches("/api/v1/public/businesses/test/catalog/items"));
        assertTrue(PublicApiEndpoints.matches(
                "/api/v1/public/businesses/test/catalog/items?limit=48&cursor=abc"));
        assertFalse(PublicApiEndpoints.matches("/api/v1/items"));
        assertFalse(PublicApiEndpoints.matches("/api/v1/auth/login"));
    }
}
