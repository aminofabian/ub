package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.finance.application.CashDrawerSummaryService;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.posdraft.application.PosDraftService;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.ShiftAuditLogRepository;
import zelisline.ub.sales.repository.ShiftDenominationRepository;
import zelisline.ub.sales.repository.ShiftExpenseRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.till.repository.TillDeviceRepository;

/**
 * Turning shifts back on closes the hidden shifts the system ran while they were off. Nobody
 * counted those drawers, so the close takes the expected cash as the count: no variance, no
 * journal entry, and no "shift closed" ping for a shift nobody opened.
 */
class ShiftCloseAutoManagedTest {

    private static final String BUSINESS = "biz-1";
    private static final String OWNER = "owner-1";
    private static final String CASHIER = "cashier-1";

    private final ShiftRepository shifts = mock(ShiftRepository.class);
    private final ShiftAuditLogRepository auditLogs = mock(ShiftAuditLogRepository.class);
    private final CashDrawerSummaryService summaries = mock(CashDrawerSummaryService.class);
    private final CashDrawerLedgerService ledger = mock(CashDrawerLedgerService.class);
    private final DrawoutService drawouts = mock(DrawoutService.class);
    private final LedgerPostingPort ledgerPosting = mock(LedgerPostingPort.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final PosDraftService drafts = mock(PosDraftService.class);

    private final ShiftService service = new ShiftService(
            shifts,
            mock(ShiftDenominationRepository.class),
            auditLogs,
            mock(ShiftExpenseRepository.class),
            mock(BranchRepository.class),
            mock(UserRepository.class),
            mock(TillDeviceRepository.class),
            mock(OpenShiftResolver.class),
            mock(BranchResolutionService.class),
            summaries,
            ledger,
            drawouts,
            ledgerPosting,
            mock(LedgerAccountResolver.class),
            events,
            auditPublisher,
            new AuditEventBuilder(new ObjectMapper()),
            drafts);

    @Test
    void closesTheHiddenShiftAtItsExpectedCashWithNoVariance() {
        Shift hidden = hiddenShift("shift-1", "1250.00");
        whenOpenHiddenShifts(hidden);

        int closed = service.closeAutoManagedShifts(BUSINESS, OWNER);

        assertThat(closed).isEqualTo(1);
        assertThat(hidden.getStatus()).isEqualTo(SalesConstants.SHIFT_STATUS_CLOSED);
        assertThat(hidden.getCountedClosingCash()).isEqualByComparingTo("1250.00");
        assertThat(hidden.getClosingVariance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(hidden.getCashTakenOut()).isNull();
        assertThat(hidden.getClosedBy()).isEqualTo(OWNER);
        assertThat(hidden.getClosedAt()).isNotNull();
        assertThat(hidden.getClosingNotes()).isEqualTo(ShiftService.AUTO_CLOSE_NOTE);
        verify(shifts).save(hidden);
    }

    @Test
    void doesTheSameBookkeepingAsAnyOtherShiftClose() {
        Shift hidden = hiddenShift("shift-1", "300.00");
        whenOpenHiddenShifts(hidden);

        service.closeAutoManagedShifts(BUSINESS, OWNER);

        verify(drawouts).applyUnappliedLiveDrawouts(hidden);
        verify(drafts).cancelPendingForClosedShift(BUSINESS, hidden, OWNER);
        verify(ledger).ensureLedgerInitialized("shift-1");
        verify(summaries).upsertForClosedShift(hidden);
        verify(auditLogs).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void postsNoVarianceJournalAndPublishesNoShiftClosedEvent() {
        whenOpenHiddenShifts(hiddenShift("shift-1", "300.00"));

        service.closeAutoManagedShifts(BUSINESS, OWNER);

        verifyNoInteractions(ledgerPosting, events);
    }

    @Test
    void closesEveryTillsHiddenShift() {
        Shift front = hiddenShift("shift-1", "100.00");
        Shift back = hiddenShift("shift-2", "200.00");
        whenOpenHiddenShifts(front, back);

        assertThat(service.closeAutoManagedShifts(BUSINESS, OWNER)).isEqualTo(2);
        assertThat(front.getStatus()).isEqualTo(SalesConstants.SHIFT_STATUS_CLOSED);
        assertThat(back.getStatus()).isEqualTo(SalesConstants.SHIFT_STATUS_CLOSED);
    }

    @Test
    void skipsAShiftSomeoneElseClosedInTheMeantime() {
        Shift hidden = hiddenShift("shift-1", "100.00");
        when(shifts.findByBusinessIdAndStatusAndAutoManagedTrue(BUSINESS, SalesConstants.SHIFT_STATUS_OPEN))
                .thenReturn(List.of(hidden));
        Shift alreadyClosed = hiddenShift("shift-1", "100.00");
        alreadyClosed.setStatus(SalesConstants.SHIFT_STATUS_CLOSED);
        when(shifts.findByIdAndBusinessIdForUpdate("shift-1", BUSINESS)).thenReturn(Optional.of(alreadyClosed));

        assertThat(service.closeAutoManagedShifts(BUSINESS, OWNER)).isZero();
        verify(shifts, never()).save(any());
        verifyNoInteractions(drafts, summaries);
    }

    @Test
    void aPlatformAdminEditRecordsTheShiftsOwnCashierAsTheCloser() {
        Shift hidden = hiddenShift("shift-1", "100.00");
        whenOpenHiddenShifts(hidden);

        service.closeAutoManagedShifts(BUSINESS, null);

        assertThat(hidden.getClosedBy()).isEqualTo(CASHIER);
    }

    @Test
    void nothingToCloseIsFine() {
        when(shifts.findByBusinessIdAndStatusAndAutoManagedTrue(BUSINESS, SalesConstants.SHIFT_STATUS_OPEN))
                .thenReturn(List.of());

        assertThat(service.closeAutoManagedShifts(BUSINESS, OWNER)).isZero();
        verifyNoInteractions(drafts, summaries, ledger, drawouts);
    }

    private void whenOpenHiddenShifts(Shift... open) {
        when(shifts.findByBusinessIdAndStatusAndAutoManagedTrue(BUSINESS, SalesConstants.SHIFT_STATUS_OPEN))
                .thenReturn(List.of(open));
        for (Shift s : open) {
            when(shifts.findByIdAndBusinessIdForUpdate(s.getId(), BUSINESS)).thenReturn(Optional.of(s));
        }
    }

    private static Shift hiddenShift(String id, String expectedCash) {
        Shift s = new Shift();
        s.setId(id);
        s.setBusinessId(BUSINESS);
        s.setBranchId("branch-1");
        s.setOpenedBy(CASHIER);
        s.setStatus(SalesConstants.SHIFT_STATUS_OPEN);
        s.setOpeningCash(BigDecimal.ZERO);
        s.setExpectedClosingCash(new BigDecimal(expectedCash));
        s.setAutoManaged(true);
        return s;
    }
}
