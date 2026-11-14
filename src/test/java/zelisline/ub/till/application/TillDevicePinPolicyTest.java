package zelisline.ub.till.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.till.repository.TillDeviceRepository;

class TillDevicePinPolicyTest {

    private TillDeviceRepository tillDeviceRepository;
    private TillDeviceService service;

    @BeforeEach
    void setUp() {
        tillDeviceRepository = mock(TillDeviceRepository.class);
        service = new TillDeviceService(tillDeviceRepository, mock(BranchRepository.class));
    }

    @Test
    void allowsWhenBranchHasNoRegisteredTills() {
        when(tillDeviceRepository.existsByBusinessIdAndBranchIdAndRevokedAtIsNull("biz", "br"))
                .thenReturn(false);
        assertDoesNotThrow(() -> service.assertPinLoginAllowed("biz", "br", null));
        assertDoesNotThrow(() -> service.assertPinLoginAllowed("biz", "br", "any-device-key-01"));
    }

    @Test
    void allowsWhenDeviceIsRegistered() {
        when(tillDeviceRepository.existsByBusinessIdAndBranchIdAndRevokedAtIsNull("biz", "br"))
                .thenReturn(true);
        when(tillDeviceRepository.existsByBusinessIdAndBranchIdAndDeviceKeyAndRevokedAtIsNull(
                        "biz", "br", "registered-device-01"))
                .thenReturn(true);
        assertDoesNotThrow(() -> service.assertPinLoginAllowed("biz", "br", "registered-device-01"));
    }

    @Test
    void rejectsMissingOrUnknownDeviceWhenBranchHasTills() {
        when(tillDeviceRepository.existsByBusinessIdAndBranchIdAndRevokedAtIsNull("biz", "br"))
                .thenReturn(true);
        when(tillDeviceRepository.existsByBusinessIdAndBranchIdAndDeviceKeyAndRevokedAtIsNull(
                        "biz", "br", "unknown-device-key1"))
                .thenReturn(false);

        ResponseStatusException missing = assertThrows(
                ResponseStatusException.class,
                () -> service.assertPinLoginAllowed("biz", "br", null)
        );
        assertEquals(HttpStatus.FORBIDDEN, missing.getStatusCode());
        assertEquals(TillDeviceService.TILL_DEVICE_NOT_REGISTERED_DETAIL, missing.getReason());

        ResponseStatusException unknown = assertThrows(
                ResponseStatusException.class,
                () -> service.assertPinLoginAllowed("biz", "br", "unknown-device-key1")
        );
        assertEquals(HttpStatus.FORBIDDEN, unknown.getStatusCode());
    }

    @Test
    void theRuleAppliesOnlyToPeopleWhoWorkATill() {
        // A barber who can only see their own earnings is not using a till.
        assertEquals(false, TillDeviceService.worksATill(java.util.List.of("commissions.read_own")));
        assertEquals(false, TillDeviceService.worksATill(java.util.List.of()));
        // Anyone who can sell, refund, void or run a shift is.
        assertEquals(true, TillDeviceService.worksATill(java.util.List.of("commissions.read_own", "sales.sell")));
        assertEquals(true, TillDeviceService.worksATill(java.util.List.of("SHIFTS.OPEN")));
        assertEquals(true, TillDeviceService.worksATill(java.util.List.of("sales.refund.create")));
        // Permissions that could not be read never loosen the rule.
        assertEquals(true, TillDeviceService.worksATill(null));
    }
}
