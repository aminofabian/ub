package zelisline.ub.till.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TillDeviceServiceTest {

    @Test
    void resolveDeviceKeyPrefersBodyOverHeader() {
        assertEquals(
                "body-device-key-01",
                TillDeviceService.resolveDeviceKey("body-device-key-01", "header-device-key-01")
        );
    }

    @Test
    void resolveDeviceKeyFallsBackToHeader() {
        assertEquals(
                "header-device-key-01",
                TillDeviceService.resolveDeviceKey("  ", "header-device-key-01")
        );
    }

    @Test
    void resolveDeviceKeyRejectsMissing() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> TillDeviceService.resolveDeviceKey(null, null)
        );
        assertTrue(ex.getReason() != null && ex.getReason().contains("deviceKey"));
    }

    @Test
    void resolveLabelDefaultsFromDeviceKey() {
        assertEquals(
                "Till a1b2c3d4",
                TillDeviceService.resolveLabel(null, "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        );
    }

    @Test
    void resolveLabelUsesProvided() {
        assertEquals("Counter 2", TillDeviceService.resolveLabel(" Counter 2 ", "unused-key-xx"));
    }
}
