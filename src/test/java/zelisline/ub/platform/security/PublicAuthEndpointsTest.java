package zelisline.ub.platform.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PublicAuthEndpointsTest {

    @Test
    void unlockPinIsPublic() {
        assertTrue(PublicAuthEndpoints.matches("/api/v1/auth/unlock-pin"));
        assertTrue(PublicAuthEndpoints.matches("/api/v1/auth/login-pin"));
        assertFalse(PublicAuthEndpoints.matches("/api/v1/till-devices"));
    }
}
