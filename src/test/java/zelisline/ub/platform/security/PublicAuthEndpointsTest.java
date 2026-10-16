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

    @Test
    void shopperPhoneAuthRoutesBypassJwtFilter() {
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/shopper/auth/send-code"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/shopper/auth/verify-code"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/shopper/auth/session"));
        assertFalse(PublicAuthEndpoints.matches("/api/v1/me/shopper"));
    }

    @Test
    void apexShopSearchBypassesJwtFilter() {
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/shops/search"));
        assertFalse(PublicAuthEndpoints.matches("/api/v1/public/shops/"));
    }

    @Test
    void magicLinkRoutesBypassJwtFilter() {
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/tills/review"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/tills/approve"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/tills/dismiss"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/drawouts/review"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/drawouts/approve"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/drawouts/reject"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/tills/review?token=abc"));
        assertFalse(PublicAuthEndpoints.matches("/api/v1/tills/devices"));
        assertFalse(PublicAuthEndpoints.matches("/api/v1/public/shops/"));
    }

    @Test
    void googleOauthRoutesBypassJwtFilter() {
        assertTrue(PublicAuthEndpoints.matches("/api/v1/auth/oauth/google/start"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/auth/oauth/google/callback"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/auth/oauth/google"));
        assertFalse(PublicAuthEndpoints.matches("/api/v1/auth/oauth/google"));
    }

    @Test
    void marketplaceDirectoryBypassesJwtFilter() {
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/marketplace/suppliers/search"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/marketplace/products/search?q=rice"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/marketplace/locations"));
        assertFalse(PublicAuthEndpoints.matches("/api/v1/marketplace"));
    }

    @Test
    void anonymousPublicReadsBypassJwtFilter() {
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/credits/tabs/0712345678"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/credits/payer-claims/lookup"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/credits/payment-claims/abc123"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/support/threads"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/barcode/0792382313648"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/page-seals/customer-tab/0712345678"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/public/onboarding-sequence/mute?token=abc"));
    }

    @Test
    void principalAwareStorefrontRoutesKeepAuthenticating() {
        // Storefront/shopper checkout reads the signed-in shopper, so it must stay
        // on the authentication path (a valid token has to be parsed, not skipped).
        assertFalse(PublicAuthEndpoints.matches("/api/v1/public/businesses/acme/carts"));
        assertFalse(PublicAuthEndpoints.matches("/api/v1/public/businesses/acme/orders"));
    }
}
