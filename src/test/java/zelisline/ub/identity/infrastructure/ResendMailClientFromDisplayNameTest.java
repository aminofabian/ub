package zelisline.ub.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ResendMailClientFromDisplayNameTest {

    @Test
    void replacesDisplayNameOnAngleAddress() {
        assertEquals(
                "Palmart <noreply@palmart.co.ke>",
                ResendMailClient.withDisplayName("UB <noreply@palmart.co.ke>", "Palmart"));
        assertEquals(
                "Palmart <noreply@palmart.co.ke>",
                ResendMailClient.withDisplayName("Kiosk <noreply@palmart.co.ke>", "Palmart"));
    }

    @Test
    void wrapsBareEmail() {
        assertEquals(
                "Palmart <noreply@palmart.co.ke>",
                ResendMailClient.withDisplayName("noreply@palmart.co.ke", "Palmart"));
    }

    @Test
    void leavesBaseWhenDisplayNameBlank() {
        assertEquals(
                "UB <noreply@palmart.co.ke>",
                ResendMailClient.withDisplayName("UB <noreply@palmart.co.ke>", "  "));
    }
}
