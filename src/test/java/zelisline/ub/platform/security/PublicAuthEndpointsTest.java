package zelisline.ub.platform.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PublicAuthEndpointsTest {

    @Test
    void supplierPortalClaimRoutesBypassJwtFilter() {
        assertTrue(PublicAuthEndpoints.matches("/api/v1/supplier-portal/auth/login"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/supplier-portal/auth/claim/config"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/supplier-portal/auth/claim/send-code"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/supplier-portal/auth/claim/verify-code"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/supplier-portal/auth/claim/verify-invite"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/supplier-portal/auth/claim/complete"));
        assertFalse(PublicAuthEndpoints.matches("/api/v1/supplier-portal/profile"));
    }
}
