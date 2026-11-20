package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.SupplierLicenceRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.TemperatureRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.TemperatureResponse;
import zelisline.ub.pharmacy.domain.PharmacySupplierLicence;
import zelisline.ub.pharmacy.domain.PharmacyTemperatureLog;
import zelisline.ub.pharmacy.repository.PharmacyAdverseEventRepository;
import zelisline.ub.pharmacy.repository.PharmacyPatientRepository;
import zelisline.ub.pharmacy.repository.PharmacySupplierLicenceRepository;
import zelisline.ub.pharmacy.repository.PharmacyTemperatureLogRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

class PharmacyComplianceServiceTest {

    private static final String BIZ = "biz-1";

    private final PharmacyTemperatureLogRepository temperatureRepository = mock(PharmacyTemperatureLogRepository.class);
    private final PharmacySupplierLicenceRepository licenceRepository = mock(PharmacySupplierLicenceRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PharmacyComplianceService service = new PharmacyComplianceService(
            temperatureRepository,
            licenceRepository,
            mock(PharmacyAdverseEventRepository.class),
            mock(PharmacyPatientRepository.class),
            supplierRepository,
            branchRepository,
            mock(ItemRepository.class),
            userRepository,
            mock(AuditEventPublisher.class),
            mock(AuditEventBuilder.class));

    private void branchExists() {
        when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull("branch-1", BIZ))
                .thenReturn(Optional.of(new Branch()));
        when(userRepository.findAllById(any())).thenReturn(List.of());
    }

    private static TemperatureRequest reading(String value, String note) {
        return new TemperatureRequest(
                "branch-1", "Fridge", new BigDecimal(value), new BigDecimal("2"), new BigDecimal("8"), note);
    }

    @Test
    void aReadingInsideTheRangeIsSavedAsNormal() {
        branchExists();
        TemperatureResponse out = service.recordTemperature(BIZ, reading("5.5", null), "user-1");
        assertFalse(out.outOfRange());
        verify(temperatureRepository).save(any(PharmacyTemperatureLog.class));
    }

    @Test
    void aReadingOutsideTheRangeNeedsANoteOnWhatWasDone() {
        branchExists();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recordTemperature(BIZ, reading("11", " "), "user-1"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatusCode().value());
        verify(temperatureRepository, never()).save(any(PharmacyTemperatureLog.class));

        TemperatureResponse out = service.recordTemperature(BIZ, reading("11", "Moved stock to the second fridge"), "user-1");
        assertTrue(out.outOfRange());
    }

    @Test
    void aReadingForAnotherBusinesssBranchIsRefused() {
        when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull("branch-1", BIZ)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recordTemperature(BIZ, reading("5", null), "user-1"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatusCode().value());
        verify(temperatureRepository, never()).save(any(PharmacyTemperatureLog.class));
    }

    @Test
    void aLicenceCannotBeRecordedAgainstAnotherBusinesssSupplier() {
        when(supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull("supplier-elsewhere", BIZ))
                .thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.saveSupplierLicence(
                        BIZ, "supplier-elsewhere", new SupplierLicenceRequest("W-1", null, null), "user-1"));
        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getStatusCode().value());
        verify(licenceRepository, never()).save(any(PharmacySupplierLicence.class));
    }
}
