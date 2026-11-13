package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.platform.web.SetupRequiredException;
import zelisline.ub.platform.web.SetupRequirement;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.domain.ShiftAuditLog;
import zelisline.ub.sales.repository.ShiftAuditLogRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

class AutoShiftServiceTest {

    private static final String BUSINESS = "biz-1";
    private static final String BRANCH = "branch-1";
    private static final String USER = "user-1";
    private static final String TILL = "till-device-0001";

    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final BranchRepository branches = mock(BranchRepository.class);
    private final ShiftRepository shifts = mock(ShiftRepository.class);
    private final ShiftAuditLogRepository auditLogs = mock(ShiftAuditLogRepository.class);
    private final OpenShiftResolver resolver = mock(OpenShiftResolver.class);
    private final CashDrawerLedgerService ledger = mock(CashDrawerLedgerService.class);
    private final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
    private final AuditEventBuilder builder = new AuditEventBuilder(new ObjectMapper());

    private final AutoShiftService service =
            new AutoShiftService(flags, branches, shifts, auditLogs, resolver, ledger, publisher, builder);

    @BeforeEach
    void persistAssignsAnId() {
        // The entity gets its id when it is persisted; the mock repository has to do the same.
        when(shifts.saveAndFlush(any(Shift.class))).thenAnswer(inv -> {
            Shift s = inv.getArgument(0);
            s.setId(UUID.randomUUID().toString());
            return s;
        });
    }

    @Test
    void shiftsOnLeavesTheCallerToAskForAShift() {
        when(flags.isShiftsDisabled(BUSINESS)).thenReturn(false);

        Optional<Shift> result = service.openIfShiftsDisabled(BUSINESS, BRANCH, TILL, USER);

        assertThat(result).isEmpty();
        verifyNoInteractions(branches, shifts, auditLogs, resolver, ledger, publisher);
    }

    @Test
    void shiftsOffOpensAHiddenZeroFloatShiftOnTheTill() {
        shiftsOffWithNothingOpen(TILL);

        Shift s = service.openIfShiftsDisabled(BUSINESS, BRANCH, TILL, USER).orElseThrow();

        assertThat(s.isAutoManaged()).isTrue();
        assertThat(s.getBusinessId()).isEqualTo(BUSINESS);
        assertThat(s.getBranchId()).isEqualTo(BRANCH);
        assertThat(s.getOpenedBy()).isEqualTo(USER);
        assertThat(s.getTillDeviceKey()).isEqualTo(TILL);
        assertThat(s.getStatus()).isEqualTo(SalesConstants.SHIFT_STATUS_OPEN);
        assertThat(s.getOpeningCash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.getExpectedClosingCash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.getOpeningNotes()).isEqualTo(AutoShiftService.OPENING_NOTE);
        verify(ledger).recordOpening(eq(s.getId()), isNull(), any(BigDecimal.class), eq(USER));
        verify(publisher).publish(any());
    }

    @Test
    void theLegacyAuditTrailSaysItWasAutomatic() {
        shiftsOffWithNothingOpen(TILL);

        service.openIfShiftsDisabled(BUSINESS, BRANCH, TILL, USER);

        ArgumentCaptor<ShiftAuditLog> captured = ArgumentCaptor.forClass(ShiftAuditLog.class);
        verify(auditLogs).save(captured.capture());
        assertThat(captured.getValue().getEventType()).isEqualTo(SalesConstants.AUDIT_SHIFT_OPENED);
        assertThat(captured.getValue().getPerformedBy()).isEqualTo(USER);
        assertThat(captured.getValue().getMetadata()).contains("\"autoManaged\":true");
    }

    @Test
    void aTillWithoutADeviceKeyGetsTheSharedBranchShift() {
        shiftsOffWithNothingOpen(null);

        Shift s = service.openIfShiftsDisabled(BUSINESS, BRANCH, null, USER).orElseThrow();

        assertThat(s.getTillDeviceKey()).isNull();
    }

    @Test
    void theLoserOfARaceReusesTheWinnersShiftInsteadOfOpeningASecond() {
        when(flags.isShiftsDisabled(BUSINESS)).thenReturn(true);
        when(resolver.resolveTillKeyForOpen(BUSINESS, BRANCH, TILL)).thenReturn(TILL);
        when(branches.findByIdAndBusinessIdForUpdate(BRANCH, BUSINESS)).thenReturn(Optional.of(new Branch()));
        Shift winners = new Shift();
        winners.setId("winner");
        winners.setAutoManaged(true);
        when(resolver.findOpenForUpdate(BUSINESS, BRANCH, TILL)).thenReturn(Optional.of(winners));

        Optional<Shift> result = service.openIfShiftsDisabled(BUSINESS, BRANCH, TILL, USER);

        assertThat(result).containsSame(winners);
        verify(shifts, never()).saveAndFlush(any(Shift.class));
        verifyNoInteractions(ledger, publisher);
    }

    @Test
    void theBranchIsLockedBeforeLookingAgain() {
        shiftsOffWithNothingOpen(TILL);

        service.openIfShiftsDisabled(BUSINESS, BRANCH, TILL, USER);

        // A locking read after the branch lock is what sees the other till's fresh commit.
        InOrder order = inOrder(branches, resolver, shifts);
        order.verify(branches).findByIdAndBusinessIdForUpdate(BRANCH, BUSINESS);
        order.verify(resolver).findOpenForUpdate(BUSINESS, BRANCH, TILL);
        order.verify(shifts).saveAndFlush(any(Shift.class));
    }

    @Test
    void anUnregisteredTillStillCannotSellWhenShiftsAreOff() {
        when(flags.isShiftsDisabled(BUSINESS)).thenReturn(true);
        when(resolver.resolveTillKeyForOpen(BUSINESS, BRANCH, TILL))
                .thenThrow(new SetupRequiredException(HttpStatus.FORBIDDEN, SetupRequirement.TILL, "Register this till"));

        assertThatThrownBy(() -> service.openIfShiftsDisabled(BUSINESS, BRANCH, TILL, USER))
                .isInstanceOfSatisfying(SetupRequiredException.class,
                        ex -> assertThat(ex.getRequirement()).isEqualTo(SetupRequirement.TILL));
        verify(shifts, never()).saveAndFlush(any(Shift.class));
    }

    @Test
    void aBranchThatVanishedOpensNothing() {
        when(flags.isShiftsDisabled(BUSINESS)).thenReturn(true);
        when(resolver.resolveTillKeyForOpen(BUSINESS, BRANCH, TILL)).thenReturn(TILL);
        when(branches.findByIdAndBusinessIdForUpdate(BRANCH, BUSINESS)).thenReturn(Optional.empty());

        assertThat(service.openIfShiftsDisabled(BUSINESS, BRANCH, TILL, USER)).isEmpty();
        verify(shifts, never()).saveAndFlush(any(Shift.class));
    }

    private void shiftsOffWithNothingOpen(String tillKey) {
        when(flags.isShiftsDisabled(BUSINESS)).thenReturn(true);
        when(resolver.resolveTillKeyForOpen(eq(BUSINESS), eq(BRANCH), any())).thenReturn(tillKey);
        when(branches.findByIdAndBusinessIdForUpdate(BRANCH, BUSINESS)).thenReturn(Optional.of(new Branch()));
        when(resolver.findOpenForUpdate(eq(BUSINESS), eq(BRANCH), any())).thenReturn(Optional.empty());
    }
}
