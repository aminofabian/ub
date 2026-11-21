package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;

class DesktopTillPolicyTest {

    private final ItemMedicineDetailsRepository repository = mock(ItemMedicineDetailsRepository.class);
    private final zelisline.ub.clinic.repository.ClinicSettingsRepository clinicSettings =
            mock(zelisline.ub.clinic.repository.ClinicSettingsRepository.class);
    private final DesktopTillPolicy policy = new DesktopTillPolicy(repository, clinicSettings);

    @Test
    void aShopWithNoMedicinesMayRunADesktopTill() {
        when(repository.existsByBusinessId("biz-grocer")).thenReturn(false);
        assertTrue(policy.desktopTillAllowed("biz-grocer"));
        policy.assertDesktopTillAllowed("biz-grocer");
    }

    @Test
    void aShopWithMedicinesIsRefusedWithTheCodeTheDesktopAppLooksFor() {
        when(repository.existsByBusinessId("biz-chemist")).thenReturn(true);
        assertFalse(policy.desktopTillAllowed("biz-chemist"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> policy.assertDesktopTillAllowed("biz-chemist"));
        assertEquals(HttpStatus.CONFLICT.value(), ex.getStatusCode().value());
        assertTrue(ex.getReason().startsWith(DesktopTillPolicy.REFUSAL_CODE));
    }

    @Test
    void oneShopsMedicinesDoNotBlockAnotherShop() {
        when(repository.existsByBusinessId("biz-chemist")).thenReturn(true);
        when(repository.existsByBusinessId("biz-grocer")).thenReturn(false);
        assertTrue(policy.desktopTillAllowed("biz-grocer"));
    }

    @Test
    void aShopRunningAClinicIsRefusedEvenWithNoMedicines() {
        when(repository.existsByBusinessId("biz-clinic")).thenReturn(false);
        when(clinicSettings.existsByBusinessIdAndEnabledTrue("biz-clinic")).thenReturn(true);
        assertFalse(policy.desktopTillAllowed("biz-clinic"));
        assertTrue(policy.desktopTillAllowed("biz-grocer"));
    }
}
