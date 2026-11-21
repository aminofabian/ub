package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.Owing;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordLine;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.PharmacyPatientRepository;

class DispensingFollowUpServiceTest {

    private static final String BIZ = "biz-1";

    private final DispensingRecordRepository recordRepository = mock(DispensingRecordRepository.class);
    private final DispensingRecordLineRepository lineRepository = mock(DispensingRecordLineRepository.class);
    private final zelisline.ub.pharmacy.repository.DispensingRecordCorrectionRepository correctionRepository =
            org.mockito.Mockito.mock(zelisline.ub.pharmacy.repository.DispensingRecordCorrectionRepository.class);
    private final DispensingFollowUpService service = new DispensingFollowUpService(
            recordRepository,
            lineRepository,
            mock(PharmacyPatientRepository.class),
            mock(AuditEventPublisher.class),
            mock(AuditEventBuilder.class),
            correctionRepository);

    private static DispensingRecord record(String id, String branchId, String patientId, Instant voidedAt) {
        DispensingRecord r = new DispensingRecord();
        r.setId(id);
        r.setBusinessId(BIZ);
        r.setBranchId(branchId);
        r.setSaleId("sale-" + id);
        r.setPatientId(patientId);
        r.setPrescriberName("Dr Wanjiru");
        r.setVoidedAt(voidedAt);
        r.setCreatedAt(Instant.now());
        return r;
    }

    private static DispensingRecordLine line(String id, String recordId, String qty, String prescribed, String settles) {
        DispensingRecordLine l = new DispensingRecordLine();
        l.setId(id);
        l.setRecordId(recordId);
        l.setBusinessId(BIZ);
        l.setItemId("item-1");
        l.setItemName("Amoxicillin");
        l.setQuantity(new BigDecimal(qty));
        l.setQuantityPrescribed(prescribed == null ? null : new BigDecimal(prescribed));
        l.setSettlesLineId(settles);
        return l;
    }

    private void given(List<DispensingRecordLine> partial, List<DispensingRecordLine> settling, List<DispensingRecord> records) {
        when(lineRepository.findPartialSupplies(eq(BIZ), any(Instant.class), any())).thenReturn(partial);
        when(lineRepository.findByBusinessIdAndSettlesLineIdIn(eq(BIZ), any())).thenReturn(settling);
        when(recordRepository.findByBusinessIdAndIdIn(eq(BIZ), any())).thenReturn(records);
    }

    @Test
    void aPartSupplyLeavesABalanceUntilLaterSalesSupplyIt() {
        given(
                List.of(line("l1", "r1", "10", "30", null)),
                List.of(line("l2", "r2", "5", null, "l1")),
                List.of(record("r1", "branch-a", "p1", null), record("r2", "branch-a", "p1", null)));
        List<Owing> out = service.owings(BIZ, null, "viewer");
        assertEquals(1, out.size());
        assertEquals(0, new BigDecimal("15").compareTo(out.getFirst().quantityOutstanding()));
        assertEquals(0, new BigDecimal("15").compareTo(out.getFirst().quantitySupplied()));
    }

    @Test
    void aBalanceSuppliedInFullIsNoLongerOwed() {
        given(
                List.of(line("l1", "r1", "10", "30", null)),
                List.of(line("l2", "r2", "20", null, "l1")),
                List.of(record("r1", "branch-a", "p1", null), record("r2", "branch-a", "p1", null)));
        assertTrue(service.owings(BIZ, null, "viewer").isEmpty());
    }

    @Test
    void aVoidedSaleNeitherOwesNorSettles() {
        given(
                List.of(line("l1", "r1", "10", "30", null), line("l3", "r3", "1", "2", null)),
                List.of(line("l2", "r2", "20", null, "l1")),
                List.of(
                        record("r1", "branch-a", "p1", null),
                        record("r2", "branch-a", "p1", Instant.now()),
                        record("r3", "branch-a", "p1", Instant.now())));
        List<Owing> out = service.owings(BIZ, null, "viewer");
        assertEquals(1, out.size());
        assertEquals("l1", out.getFirst().lineId());
        assertEquals(0, new BigDecimal("20").compareTo(out.getFirst().quantityOutstanding()));
    }

    @Test
    void aBranchTiedViewerSeesOnlyTheirBranchAndAPatientFilterSeesOnlyThatPatient() {
        given(
                List.of(line("l1", "r1", "10", "30", null), line("l4", "r4", "1", "5", null)),
                List.of(),
                List.of(record("r1", "branch-a", "p1", null), record("r4", "branch-b", "p2", null)));
        assertEquals(1, service.owings(BIZ, "branch-b", "viewer").size());
        assertEquals("l4", service.owings(BIZ, "branch-b", "viewer").getFirst().lineId());
        assertEquals("l1", service.owingsForPatient(BIZ, "p1", "viewer").getFirst().lineId());
        assertTrue(service.owingsForPatient(BIZ, "someone-else", "viewer").isEmpty());
    }

    @Test
    void closingAFollowUpTakesTheLineOffBothListsAndSaysWhyOnTheRecord() {
        zelisline.ub.pharmacy.domain.DispensingRecordLine line = new zelisline.ub.pharmacy.domain.DispensingRecordLine();
        line.setId("line-9");
        line.setRecordId("rec-9");
        line.setBusinessId("biz-1");
        line.setItemName("Metformin 500mg");
        line.setRefillDueOn(java.time.LocalDate.of(2026, 12, 1));
        zelisline.ub.pharmacy.domain.DispensingRecord record = new zelisline.ub.pharmacy.domain.DispensingRecord();
        record.setId("rec-9");
        record.setBusinessId("biz-1");
        record.setBranchId("branch-a");
        org.mockito.Mockito.when(lineRepository.findByIdAndBusinessId("line-9", "biz-1"))
                .thenReturn(java.util.Optional.of(line));
        org.mockito.Mockito.when(recordRepository.findByIdAndBusinessId("rec-9", "biz-1"))
                .thenReturn(java.util.Optional.of(record));

        service.close("biz-1", "line-9", " Got the rest elsewhere ", "user-1", null);

        org.junit.jupiter.api.Assertions.assertNotNull(line.getFollowUpClosedAt());
        org.junit.jupiter.api.Assertions.assertEquals("user-1", line.getFollowUpClosedBy());
        org.junit.jupiter.api.Assertions.assertEquals("Got the rest elsewhere", line.getFollowUpNote());
        org.junit.jupiter.api.Assertions.assertNull(line.getRefillDueOn());
        org.mockito.Mockito.verify(correctionRepository)
                .save(org.mockito.ArgumentMatchers.any(zelisline.ub.pharmacy.domain.DispensingRecordCorrection.class));

        // Another branch's pharmacist, another shop, or no reason: nothing changes.
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.close("biz-1", "line-9", "x", "user-2", "branch-b"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.close("biz-2", "line-9", "x", "user-3", null));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.close("biz-1", "line-9", "  ", "user-1", null));
    }
}
