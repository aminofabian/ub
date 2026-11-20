package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordCorrection;
import zelisline.ub.pharmacy.repository.DispensingRecordCorrectionRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;

class DispensingRecordAmendServiceTest {

    private static final String BIZ = "biz-1";

    private final DispensingRecordRepository recordRepository = mock(DispensingRecordRepository.class);
    private final DispensingRecordCorrectionRepository correctionRepository =
            mock(DispensingRecordCorrectionRepository.class);
    private final DispensingRecordAmendService service = new DispensingRecordAmendService(
            recordRepository, correctionRepository, mock(AuditEventPublisher.class), mock(AuditEventBuilder.class));

    private DispensingRecord record() {
        DispensingRecord r = new DispensingRecord();
        r.setId("rec-1");
        r.setBusinessId(BIZ);
        r.setBranchId("branch-a");
        r.setDispensedBy("pharmacist-1");
        when(recordRepository.findByIdAndBusinessId("rec-1", BIZ)).thenReturn(Optional.of(r));
        return r;
    }

    private int refused(Runnable action) {
        return assertThrows(ResponseStatusException.class, action::run).getStatusCode().value();
    }

    @Test
    void someoneElseMayCheckARecordOnce() {
        DispensingRecord r = record();
        service.verify(BIZ, "rec-1", "pharmacist-2", null);
        assertEquals("pharmacist-2", r.getVerifiedBy());
        assertEquals(HttpStatus.CONFLICT.value(), refused(() -> service.verify(BIZ, "rec-1", "pharmacist-3", null)));
        assertEquals("pharmacist-2", r.getVerifiedBy());
    }

    @Test
    void theDispenserCannotCheckTheirOwnRecord() {
        DispensingRecord r = record();
        assertEquals(HttpStatus.FORBIDDEN.value(), refused(() -> service.verify(BIZ, "rec-1", "pharmacist-1", null)));
        assertEquals(null, r.getVerifiedBy());
    }

    @Test
    void aVoidedRecordIsNotChecked() {
        record().setVoidedAt(Instant.now());
        assertEquals(HttpStatus.CONFLICT.value(), refused(() -> service.verify(BIZ, "rec-1", "pharmacist-2", null)));
    }

    @Test
    void anotherBusinesssOrAnotherBranchsRecordIsNotFound() {
        when(recordRepository.findByIdAndBusinessId("rec-x", BIZ)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND.value(), refused(() -> service.verify(BIZ, "rec-x", "pharmacist-2", null)));
        record();
        assertEquals(HttpStatus.NOT_FOUND.value(),
                refused(() -> service.addCorrection(BIZ, "rec-1", "Wrong prescriber", "pharmacist-2", "branch-b")));
        verify(correctionRepository, never()).save(any(DispensingRecordCorrection.class));
    }

    @Test
    void aCorrectionNeedsTextAndLeavesTheRecordAlone() {
        DispensingRecord r = record();
        assertEquals(HttpStatus.BAD_REQUEST.value(),
                refused(() -> service.addCorrection(BIZ, "rec-1", "  ", "pharmacist-2", null)));
        service.addCorrection(BIZ, "rec-1", "Prescriber was Dr Otieno, not Dr Wanjiru", "pharmacist-2", null);
        verify(correctionRepository).save(any(DispensingRecordCorrection.class));
        verify(recordRepository, never()).save(r);
    }
}
