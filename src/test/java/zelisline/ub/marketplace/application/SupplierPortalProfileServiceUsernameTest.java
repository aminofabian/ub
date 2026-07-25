package zelisline.ub.marketplace.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SupplierPortalProfileServiceUsernameTest {

    @Test
    void normalizeUsername_slugifiesAndStripsAt() {
        assertEquals("jamro-fresh", SupplierPortalProfileService.normalizeUsername("@Jamro Fresh"));
        assertEquals("jamro", SupplierPortalProfileService.normalizeUsername("  JAMRO  "));
    }
}
