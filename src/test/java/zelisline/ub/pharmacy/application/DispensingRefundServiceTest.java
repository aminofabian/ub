package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordCorrection;
import zelisline.ub.pharmacy.domain.DispensingRecordLine;
import zelisline.ub.pharmacy.repository.DispensingRecordCorrectionRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;

class DispensingRefundServiceTest {

    private static final String BIZ = "biz-1";
    private static final String SALE = "sale-1";

    private final DispensingRecordRepository recordRepository = mock(DispensingRecordRepository.class);
    private final DispensingRecordLineRepository lineRepository = mock(DispensingRecordLineRepository.class);
    private final DispensingRecordCorrectionRepository correctionRepository =
            mock(DispensingRecordCorrectionRepository.class);
    private final DispensingRefundService service =
            new DispensingRefundService(recordRepository, lineRepository, correctionRepository);

    private static DispensingRecord record(String id, boolean voided) {
        DispensingRecord r = new DispensingRecord();
        r.setId(id);
        r.setBusinessId(BIZ);
        r.setSaleId(SALE);
        if (voided) {
            r.setVoidedAt(Instant.now());
        }
        return r;
    }

    private static DispensingRecordLine line(String id, String recordId, String name, String qty, String saleItems) {
        DispensingRecordLine l = new DispensingRecordLine();
        l.setId(id);
        l.setRecordId(recordId);
        l.setBusinessId(BIZ);
        l.setItemName(name);
        l.setQuantity(new BigDecimal(qty));
        l.setSaleItemIds(saleItems);
        l.setRefillDueOn(LocalDate.of(2026, 12, 1));
        return l;
    }

    @Test
    void aRefundMarksWhatCameBackAndStopsTheRefillReminder() {
        DispensingRecordLine amox = line("l1", "rec-1", "Amoxicillin 500mg", "30", "si-1,si-2");
        DispensingRecordLine para = line("l2", "rec-1", "Paracetamol", "10", "si-3");
        when(recordRepository.findByBusinessIdAndSaleId(BIZ, SALE)).thenReturn(List.of(record("rec-1", false)));
        when(lineRepository.findByBusinessIdAndRecordIdInOrderByCreatedAtAscIdAsc(eq(BIZ), anyCollection()))
                .thenReturn(List.of(amox, para));
        Map<String, BigDecimal> returned = new LinkedHashMap<>();
        returned.put("si-1", new BigDecimal("4"));
        returned.put("si-2", new BigDecimal("6"));

        assertEquals(1, service.applyRefund(BIZ, SALE, returned, "user-1"));

        assertEquals(0, new BigDecimal("10").compareTo(amox.getQuantityReturned()));
        assertEquals(0, new BigDecimal("20").compareTo(amox.netQuantity()));
        assertNull(amox.getRefillDueOn());
        assertNull(para.getQuantityReturned());
        assertNotNull(para.getRefillDueOn());
        ArgumentCaptor<DispensingRecordCorrection> note = ArgumentCaptor.forClass(DispensingRecordCorrection.class);
        verify(correctionRepository).save(note.capture());
        assertEquals("rec-1", note.getValue().getRecordId());
        assertEquals(BIZ, note.getValue().getBusinessId());
        assertEquals("user-1", note.getValue().getCreatedBy());
        assertEquals("Refunded and returned: 10 of Amoxicillin 500mg.", note.getValue().getNote());
    }

    @Test
    void aSecondRefundAddsUpAndNeverExceedsWhatWasDispensed() {
        DispensingRecordLine amox = line("l1", "rec-1", "Amoxicillin 500mg", "30", "si-1");
        amox.setQuantityReturned(new BigDecimal("10"));
        when(recordRepository.findByBusinessIdAndSaleId(BIZ, SALE)).thenReturn(List.of(record("rec-1", false)));
        when(lineRepository.findByBusinessIdAndRecordIdInOrderByCreatedAtAscIdAsc(eq(BIZ), anyCollection()))
                .thenReturn(List.of(amox));

        service.applyRefund(BIZ, SALE, Map.of("si-1", new BigDecimal("25")), "user-1");

        assertEquals(0, new BigDecimal("30").compareTo(amox.getQuantityReturned()));
        assertEquals(0, amox.netQuantity().signum());
        assertTrue(amox.anyReturned());
    }

    @Test
    void aVoidedRecordOrASaleWithNoRecordIsLeftAlone() {
        when(recordRepository.findByBusinessIdAndSaleId(BIZ, SALE)).thenReturn(List.of(record("rec-9", true)));

        assertEquals(0, service.applyRefund(BIZ, SALE, Map.of("si-1", BigDecimal.ONE), "user-1"));
        assertEquals(0, service.applyRefund(BIZ, "sale-with-no-record", Map.of("si-1", BigDecimal.ONE), "user-1"));
        assertEquals(0, service.applyRefund(BIZ, SALE, Map.of(), "user-1"));

        verify(lineRepository, never()).save(any());
        verify(correctionRepository, never()).save(any());
    }

    @Test
    void anotherShopsRefundCannotReachThisShopsRecord() {
        when(recordRepository.findByBusinessIdAndSaleId(BIZ, SALE)).thenReturn(List.of(record("rec-1", false)));

        assertEquals(0, service.applyRefund("biz-2", SALE, Map.of("si-1", BigDecimal.ONE), "user-9"));

        verify(recordRepository).findByBusinessIdAndSaleId("biz-2", SALE);
        verify(lineRepository, never()).save(any());
    }
}
