package zelisline.ub.wholesale.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.trade.repository.TradeOrderRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;
import zelisline.ub.wholesale.repository.SupplierContributionRepository;
import zelisline.ub.wholesale.repository.SupplierMilestoneRepository;
import zelisline.ub.wholesale.repository.SupplierNotificationLogRepository;

/**
 * With the flags off (the default), supplier engagement reads and writes nothing. Every flag lookup returns false
 * from an unstubbed mock, which is exactly the state of a shop that never had the flags set.
 */
class SupplierEngagementFlagsOffTest {

    private static final String BUSINESS = "biz-1";
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);

    @Test
    void contributionWritesNothing() {
        SupplierContributionRepository rows = mock(SupplierContributionRepository.class);
        SupplierOrderableService orderable = mock(SupplierOrderableService.class);
        SupplierContributionService service = new SupplierContributionService(flags, rows, orders, orderable);

        service.recordAnswered(BUSINESS, "user-1", "order-1");
        // Before a save the caller is told there is nothing to check, so it does nothing after the save either.
        assertThat(service.notYetOrderable(BUSINESS, java.util.List.of("item-1"))).isNull();
        service.recordMadeOrderable(BUSINESS, "user-1", (java.util.Set<String>) null);

        verifyNoInteractions(rows, orders, orderable);
    }

    @Test
    void milestonesAreNotLookedFor() {
        SupplierMilestoneRepository rows = mock(SupplierMilestoneRepository.class);
        TradeOrderRepository trades = mock(TradeOrderRepository.class);
        BusinessRepository businesses = mock(BusinessRepository.class);
        SupplierMilestoneService service = new SupplierMilestoneService(flags, rows, orders, trades, businesses);

        service.recordDue(BUSINESS);
        service.recordFirstOrderSupplied(BUSINESS);

        verifyNoInteractions(rows, orders, trades, businesses);
    }

    @Test
    void theOverviewIsOffAndReadsNothing() {
        SupplierReadinessService readiness = mock(SupplierReadinessService.class);
        SupplierOrderableService orderable = mock(SupplierOrderableService.class);
        SupplierMilestoneService milestones = mock(SupplierMilestoneService.class);
        SupplierMilestoneRepository milestoneRows = mock(SupplierMilestoneRepository.class);
        SupplierContributionService contribution = mock(SupplierContributionService.class);
        SupplierStandingService standing = mock(SupplierStandingService.class);
        SupplierOverviewService service = new SupplierOverviewService(
                flags, readiness, orderable, milestones, milestoneRows, contribution, orders, standing);

        assertThat(service.overview(BUSINESS, "user-1").enabled()).isFalse();

        verifyNoInteractions(readiness, orderable, milestones, milestoneRows, contribution, orders, standing);
    }

    @Test
    void noNoteIsSentWhileTheNotificationsFlagIsOff() {
        SupplierNotificationLogRepository log = mock(SupplierNotificationLogRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationService mail = mock(NotificationService.class);
        when(orders.findBusinessIdsWithOrdersPlacedBefore(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(BUSINESS));
        SupplierWaitingOrderNotifier notifier = new SupplierWaitingOrderNotifier(orders, log, flags, users, mail);

        // A Monday morning in Nairobi that is not a holiday.
        int sent = notifier.sendDue(ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, NAIROBI));

        assertThat(sent).isZero();
        verifyNoInteractions(log, users, mail);
    }

    @Test
    void notesGoOutOnlyOnAWorkingMorning() {
        assertThat(SupplierWaitingOrderNotifier.canSendAt(ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, NAIROBI))).isTrue();
        // Sunday.
        assertThat(SupplierWaitingOrderNotifier.canSendAt(ZonedDateTime.of(2026, 11, 22, 10, 0, 0, 0, NAIROBI))).isFalse();
        // Jamhuri Day.
        assertThat(SupplierWaitingOrderNotifier.canSendAt(ZonedDateTime.of(2026, 12, 12, 10, 0, 0, 0, NAIROBI))).isFalse();
        // Too early and too late.
        assertThat(SupplierWaitingOrderNotifier.canSendAt(ZonedDateTime.of(2026, 11, 23, 8, 59, 0, 0, NAIROBI))).isFalse();
        assertThat(SupplierWaitingOrderNotifier.canSendAt(ZonedDateTime.of(2026, 11, 23, 12, 0, 0, 0, NAIROBI))).isFalse();
    }
}
