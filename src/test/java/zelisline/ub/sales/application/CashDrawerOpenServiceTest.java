package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventPayload;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.CashDrawerOpenLogTender;
import zelisline.ub.sales.api.dto.PatchCashDrawerOpenRequest;
import zelisline.ub.sales.api.dto.PostCashDrawerOpenRequest;
import zelisline.ub.sales.domain.CashDrawerOpen;
import zelisline.ub.sales.domain.CashDrawerOpenReason;
import zelisline.ub.sales.domain.CashDrawerOpenSource;
import zelisline.ub.sales.domain.CashDrawout;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.CashDrawerOpenRepository;
import zelisline.ub.sales.repository.CashDrawoutRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

@ExtendWith(MockitoExtension.class)
class CashDrawerOpenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T15:00:00Z");
    private static final String BIZ_A = "biz-a";
    private static final String BIZ_B = "biz-b";
    private static final String BRANCH = "branch-1";
    private static final String SHIFT = "shift-1";
    private static final String USER = "user-1";
    private static final String CLIENT = "11111111-1111-1111-1111-111111111111";

    @Mock
    private CashDrawerOpenRepository opens;
    @Mock
    private ShiftRepository shifts;
    @Mock
    private SaleRepository sales;
    @Mock
    private SalePaymentRepository salePayments;
    @Mock
    private CashDrawoutRepository cashDrawouts;
    @Mock
    private BranchRepository branches;
    @Mock
    private AuditEventPublisher auditEventPublisher;

    private CashDrawerOpenService service;

    @BeforeEach
    void setUp() {
        service = new CashDrawerOpenService(
                opens,
                shifts,
                sales,
                salePayments,
                cashDrawouts,
                branches,
                auditEventPublisher,
                new AuditEventBuilder(new ObjectMapper()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsATriggeredOpenAtTheTillsClock() {
        stubBranchAndShift(BIZ_A);
        when(opens.findByBusinessIdAndClientEventId(BIZ_A, CLIENT)).thenReturn(Optional.empty());
        when(opens.save(any(CashDrawerOpen.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant openedAt = NOW.minusSeconds(4);

        RecordedCashDrawerOpen recorded = service.record(BIZ_A, USER, "till-1", request(
                CashDrawerOpenSource.TRIGGERED, CashDrawerOpenReason.CASH_SALE, openedAt));

        assertThat(recorded.created()).isTrue();
        assertThat(recorded.open().openedAt()).isEqualTo(openedAt);
        assertThat(recorded.open().source()).isEqualTo(CashDrawerOpenSource.TRIGGERED);
        assertThat(recorded.open().sensorConfirmed()).isFalse();
        ArgumentCaptor<AuditEventPayload> audit = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditEventPublisher).publish(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo(AuditEventTypes.CASH_DRAWER_OPENED);
        assertThat(audit.getValue().businessId()).isEqualTo(BIZ_A);
        assertThat(audit.getValue().createdAt()).isEqualTo(openedAt);
    }

    @Test
    void replayOfTheSameClientEventDoesNotSaveAgain() {
        CashDrawerOpen stored = storedOpening();
        when(opens.findByBusinessIdAndClientEventId(BIZ_A, CLIENT)).thenReturn(Optional.of(stored));

        RecordedCashDrawerOpen again = service.record(BIZ_A, USER, null, request(
                CashDrawerOpenSource.TRIGGERED, CashDrawerOpenReason.CASH_SALE, NOW.minusSeconds(4)));

        assertThat(again.created()).isFalse();
        assertThat(again.open().id()).isEqualTo(stored.getId());
        verify(opens, never()).save(any());
        verify(auditEventPublisher, never()).publish(any());
    }

    @Test
    void aShiftOwnedByAnotherBusinessIsNotFound() {
        when(branches.findByIdAndBusinessIdAndDeletedAtIsNull(BRANCH, BIZ_B))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(Branch.class)));
        when(shifts.findByIdAndBusinessId(SHIFT, BIZ_B)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(BIZ_B, USER, null, request(
                CashDrawerOpenSource.KEY, CashDrawerOpenReason.KEY, NOW.minusSeconds(2))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(opens, never()).save(any());
    }

    @Test
    void aPulseCannotBeRecordedAsAKey() {
        assertThatThrownBy(() -> service.record(BIZ_A, USER, null, request(
                CashDrawerOpenSource.TRIGGERED, CashDrawerOpenReason.KEY, NOW)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anOpeningAheadOfTheServerClockIsRejected() {
        assertThatThrownBy(() -> service.record(BIZ_A, USER, null, request(
                CashDrawerOpenSource.TRIGGERED,
                CashDrawerOpenReason.UNKNOWN,
                NOW.plus(CashDrawerOpenService.FUTURE_SKEW).plusSeconds(30))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void closeStoresTheSwitchTimeAndAnotherBusinessCannotSeeTheRow() {
        CashDrawerOpen stored = storedOpening();
        when(opens.findByBusinessIdAndClientEventId(BIZ_A, CLIENT)).thenReturn(Optional.of(stored));
        when(opens.findByBusinessIdAndClientEventId(BIZ_B, CLIENT)).thenReturn(Optional.empty());
        when(opens.save(any(CashDrawerOpen.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant closedAt = stored.getOpenedAt().plusSeconds(12);

        var closed = service.close(BIZ_A, CLIENT, new PatchCashDrawerOpenRequest(closedAt, true));

        assertThat(closed.closedAt()).isEqualTo(closedAt);
        assertThat(closed.sensorConfirmed()).isTrue();
        assertThatThrownBy(() -> service.close(BIZ_B, CLIENT, new PatchCashDrawerOpenRequest(closedAt, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theDrawerCannotCloseBeforeItOpened() {
        CashDrawerOpen stored = storedOpening();
        when(opens.findByBusinessIdAndClientEventId(BIZ_A, CLIENT)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.close(
                BIZ_A,
                CLIENT,
                new PatchCashDrawerOpenRequest(stored.getOpenedAt().minusSeconds(1), null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aSaleFromAnotherBusinessIsNotAttached() {
        stubBranchAndShift(BIZ_A);
        when(opens.findByBusinessIdAndClientEventId(BIZ_A, CLIENT)).thenReturn(Optional.empty());
        String saleId = UUID.randomUUID().toString();
        when(sales.findByIdAndBusinessId(saleId, BIZ_A)).thenReturn(Optional.empty());
        PostCashDrawerOpenRequest body = new PostCashDrawerOpenRequest(
                CLIENT,
                NOW.minusSeconds(1),
                CashDrawerOpenSource.TRIGGERED,
                CashDrawerOpenReason.CASH_SALE,
                BRANCH,
                SHIFT,
                saleId,
                false);

        assertThatThrownBy(() -> service.record(BIZ_A, USER, null, body))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aNoSaleOpeningIsListedAndNotClaimedByANearbyCashSale() {
        String shiftId = "22222222-2222-2222-2222-222222222222";
        String saleId = "33333333-3333-3333-3333-333333333333";
        when(shifts.findByIdAndBusinessId(shiftId, BIZ_A)).thenReturn(Optional.of(new Shift()));
        CashDrawerOpen justOpened = loggedOpening("open-plain", NOW, null);
        justOpened.setSource(CashDrawerOpenSource.TRIGGERED);
        justOpened.setReason(CashDrawerOpenReason.UNKNOWN);
        when(opens.findByBusinessIdAndShiftIdOrderByOpenedAtAsc(BIZ_A, shiftId))
                .thenReturn(List.of(justOpened));
        when(sales.findByBusinessIdAndShiftId(BIZ_A, shiftId))
                .thenReturn(List.of(cashSale(saleId, 7L, NOW.plusSeconds(30))));
        when(salePayments.findBySaleIdInAndMethod(any(), eq(SalesConstants.PAYMENT_METHOD_CASH)))
                .thenReturn(List.of(cashPayment(saleId, "80.00")));
        when(cashDrawouts.findByShiftIdOrderByCreatedAtDesc(shiftId)).thenReturn(List.of());

        var log = service.log(BIZ_A, shiftId);

        assertThat(log.rows()).hasSize(1);
        assertThat(log.rows().get(0).matched()).isFalse();
        assertThat(log.rows().get(0).reason()).isEqualTo(CashDrawerOpenReason.UNKNOWN);
        assertThat(log.unmatched()).isEqualTo(1);
        assertThat(tender(log.tenders(), "Receipt 7").coincided()).isFalse();
    }

    @Test
    void logMatchesCashAndAnApprovedDrawoutAndLeavesAReprintUnmatched() {
        String shiftId = "22222222-2222-2222-2222-222222222222";
        String saleId = "33333333-3333-3333-3333-333333333333";
        String quietSaleId = "44444444-4444-4444-4444-444444444444";
        when(shifts.findByIdAndBusinessId(shiftId, BIZ_A)).thenReturn(Optional.of(new Shift()));
        when(shifts.findByIdAndBusinessId(shiftId, BIZ_B)).thenReturn(Optional.empty());
        when(opens.findByBusinessIdAndShiftIdOrderByOpenedAtAsc(BIZ_A, shiftId)).thenReturn(List.of(
                loggedOpening("open-1", NOW, saleId),
                loggedOpening("open-2", NOW.plusSeconds(20), saleId),
                loggedOpening("open-3", NOW.plusSeconds(40), null)));
        Sale sale = cashSale(saleId, 42L, NOW);
        Sale quiet = cashSale(quietSaleId, 99L, NOW.plusSeconds(600));
        when(sales.findByBusinessIdAndShiftId(BIZ_A, shiftId)).thenReturn(List.of(sale, quiet));
        when(salePayments.findBySaleIdInAndMethod(any(), eq(SalesConstants.PAYMENT_METHOD_CASH)))
                .thenReturn(List.of(cashPayment(saleId, "150.00"), cashPayment(quietSaleId, "40.00")));
        when(cashDrawouts.findByShiftIdOrderByCreatedAtDesc(shiftId)).thenReturn(List.of(
                drawout("draw-no", SalesConstants.DRAWOUT_STATUS_REJECTED, NOW.plusSeconds(40), "Nope"),
                drawout("draw-yes", SalesConstants.DRAWOUT_STATUS_APPROVED, NOW.plusSeconds(50), "Milk")));

        var log = service.log(BIZ_A, shiftId);

        assertThat(log.unmatched()).isEqualTo(1);
        assertThat(log.rows().get(0).matched()).isTrue();
        assertThat(log.rows().get(0).matchKind()).isEqualTo(CashDrawerOpenMatcher.KIND_CASH);
        assertThat(log.rows().get(0).matchLabel()).isEqualTo("Receipt 42");
        assertThat(log.rows().get(0).matchAmount()).isEqualByComparingTo("150.00");
        assertThat(log.rows().get(1).matched()).isFalse();
        assertThat(log.rows().get(2).matchKind()).isEqualTo(CashDrawerOpenMatcher.KIND_DRAWOUT);
        assertThat(log.rows().get(2).matchLabel()).isEqualTo("Milk");
        assertThat(tender(log.tenders(), "Receipt 42").coincided()).isTrue();
        assertThat(tender(log.tenders(), "Receipt 99").coincided()).isFalse();
        assertThat(tender(log.tenders(), "Milk").coincided()).isTrue();
        assertThat(log.tenders()).extracting(row -> row.label()).doesNotContain("Nope");
        assertThatThrownBy(() -> service.log(BIZ_B, shiftId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void stubBranchAndShift(String businessId) {
        when(branches.findByIdAndBusinessIdAndDeletedAtIsNull(BRANCH, businessId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(Branch.class)));
        Shift shift = new Shift();
        shift.setId(SHIFT);
        shift.setBusinessId(businessId);
        shift.setBranchId(BRANCH);
        when(shifts.findByIdAndBusinessId(SHIFT, businessId)).thenReturn(Optional.of(shift));
    }

    private PostCashDrawerOpenRequest request(
            CashDrawerOpenSource source,
            CashDrawerOpenReason reason,
            Instant openedAt) {
        return new PostCashDrawerOpenRequest(
                CLIENT, openedAt, source, reason, BRANCH, SHIFT, null, false);
    }

    private CashDrawerOpen storedOpening() {
        CashDrawerOpen stored = new CashDrawerOpen();
        stored.setId("row-1");
        stored.setBusinessId(BIZ_A);
        stored.setBranchId(BRANCH);
        stored.setShiftId(SHIFT);
        stored.setClientEventId(CLIENT);
        stored.setOpenedAt(NOW.minusSeconds(20));
        stored.setSource(CashDrawerOpenSource.TRIGGERED);
        stored.setReason(CashDrawerOpenReason.CASH_SALE);
        stored.setSensorConfirmed(false);
        return stored;
    }

    private static Sale cashSale(String id, long receiptNo, Instant soldAt) {
        Sale sale = new Sale();
        sale.setId(id);
        sale.setReceiptNo(receiptNo);
        sale.setSoldAt(soldAt);
        return sale;
    }

    private static SalePayment cashPayment(String saleId, String amount) {
        SalePayment payment = new SalePayment();
        payment.setSaleId(saleId);
        payment.setAmount(new BigDecimal(amount));
        return payment;
    }

    private static CashDrawerOpenLogTender tender(List<CashDrawerOpenLogTender> tenders, String label) {
        return tenders.stream().filter(row -> label.equals(row.label())).findFirst().orElseThrow();
    }

    private static CashDrawerOpen loggedOpening(String id, Instant openedAt, String saleId) {
        CashDrawerOpen row = new CashDrawerOpen();
        row.setId(id);
        row.setOpenedAt(openedAt);
        row.setSaleId(saleId);
        row.setSource(saleId == null ? CashDrawerOpenSource.KEY : CashDrawerOpenSource.TRIGGERED);
        row.setReason(saleId == null ? CashDrawerOpenReason.KEY : CashDrawerOpenReason.CASH_SALE);
        return row;
    }

    private static CashDrawout drawout(String id, String status, Instant at, String recipient) {
        CashDrawout row = new CashDrawout();
        row.setId(id);
        row.setStatus(status);
        row.setCreatedAt(at);
        row.setAmount(new BigDecimal("200"));
        row.setRecipientName(recipient);
        return row;
    }
}
