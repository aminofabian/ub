package zelisline.ub.wholesale.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.wholesale.api.dto.SupplierOverviewResponse;
import zelisline.ub.wholesale.api.dto.SupplierOverviewResponse.Contribution;
import zelisline.ub.wholesale.api.dto.SupplierOverviewResponse.Milestone;
import zelisline.ub.wholesale.api.dto.SupplierOverviewResponse.NextThing;
import zelisline.ub.wholesale.api.dto.SupplierOverviewResponse.OrderableSummary;
import zelisline.ub.wholesale.api.dto.SupplierReadinessResponse;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SalesOrderStatuses;
import zelisline.ub.wholesale.domain.SupplierMilestone;
import zelisline.ub.wholesale.repository.SalesOrderRepository;
import zelisline.ub.wholesale.repository.SupplierMilestoneRepository;

/**
 * The supplier's overview on the trade desk. Everything is read from the shop's own rows when asked; nothing here
 * runs when an order is placed, answered, dispatched or paid for. Milestones that have become true are recorded
 * here, on read, so the order path never writes them.
 */
@Service
@RequiredArgsConstructor
public class SupplierOverviewService {

    private final FeatureFlagService featureFlagService;
    private final SupplierReadinessService readinessService;
    private final SupplierOrderableService orderableService;
    private final SupplierMilestoneService milestoneService;
    private final SupplierMilestoneRepository milestoneRepository;
    private final SupplierContributionService contributionService;
    private final SalesOrderRepository salesOrderRepository;
    private final SupplierStandingService standingService;

    @Value("${supplier-engagement.kill-switch:false}")
    private boolean killSwitch;

    @Transactional
    public SupplierOverviewResponse overview(String businessId, String userId) {
        if (killSwitch || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)) {
            return SupplierOverviewResponse.off();
        }
        boolean readinessOn = featureFlagService.isEnabled(
                businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_READINESS);
        boolean milestonesOn = featureFlagService.isEnabled(
                businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_MILESTONES);

        long waiting = salesOrderRepository.countByBusinessIdAndStatus(businessId, SalesOrderStatuses.PLACED);
        java.time.Instant oldest = waiting == 0
                ? null
                : salesOrderRepository
                        .findFirstByBusinessIdAndStatusOrderByCreatedAtAsc(businessId, SalesOrderStatuses.PLACED)
                        .map(SalesOrder::getCreatedAt)
                        .orElse(null);
        long toDispatch = salesOrderRepository.countByBusinessIdAndStatus(businessId, SalesOrderStatuses.ACCEPTED)
                + salesOrderRepository.countByBusinessIdAndStatus(businessId, SalesOrderStatuses.PART_ACCEPTED);

        List<SupplierReadinessResponse.Step> steps = readinessOn
                ? readinessService.readiness(businessId).steps()
                : List.of();
        OrderableSummary products = readinessOn ? orderableService.summary(businessId) : null;

        List<Milestone> milestones = new ArrayList<>();
        Long repeatBuyers = null;
        if (milestonesOn) {
            milestoneService.recordDue(businessId);
            for (SupplierMilestone m : milestoneRepository.findByBusinessIdOrderByAchievedAtAsc(businessId)) {
                milestones.add(new Milestone(
                        m.getMilestoneKey(), SupplierMilestoneService.label(m.getMilestoneKey()), m.getAchievedAt(),
                        m.getSeenAt() != null));
            }
            repeatBuyers = (long) salesOrderRepository.findRepeatBuyerBusinessIds(businessId).size();
        }

        Contribution contribution = userId != null && contributionService.on(businessId)
                ? new Contribution(
                        contributionService.answeredWithinAWorkingDay(businessId, userId),
                        contributionService.productsMadeOrderable(businessId, userId))
                : null;

        return new SupplierOverviewResponse(
                true, nextThing(waiting, toDispatch, steps), waiting, oldest, steps, products,
                List.copyOf(milestones), repeatBuyers, contribution, recoveryGoals(businessId));
    }

    static final int RECENT_DAYS = 30;
    static final int USUAL_DAYS = 90;
    /** A dip smaller than this many points is ordinary variation and is not mentioned. */
    static final java.math.BigDecimal DIP_POINTS = java.math.BigDecimal.valueOf(5);

    /**
     * Shown only when a measure dipped: the last 30 days against the last 90, for what was supplied and what was
     * dispatched on time. A sentence with both numbers, no target and no score. Empty while standing is off or
     * either window has too few orders to say anything.
     */
    private List<String> recoveryGoals(String businessId) {
        List<String> goals = new ArrayList<>();
        try {
            var recent = standingService.standing(businessId, RECENT_DAYS);
            var usual = standingService.standing(businessId, USUAL_DAYS);
            if (!recent.enabled() || !usual.enabled()) {
                return List.of();
            }
            addGoal(goals, recent, usual, "supplies_what_it_accepts", "You supplied %s%% of what buyers asked for");
            addGoal(goals, recent, usual, "dispatches_on_time", "You dispatched %s%% of orders on time");
        } catch (RuntimeException e) {
            return List.of();
        }
        return List.copyOf(goals);
    }

    private static void addGoal(
            List<String> goals,
            zelisline.ub.wholesale.api.dto.SupplierStandingResponse recent,
            zelisline.ub.wholesale.api.dto.SupplierStandingResponse usual,
            String key, String sentence) {
        java.math.BigDecimal now = value(recent, key);
        java.math.BigDecimal before = value(usual, key);
        if (now == null || before == null || before.subtract(now).compareTo(DIP_POINTS) < 0) {
            return;
        }
        goals.add(String.format(sentence, now.stripTrailingZeros().toPlainString())
                + " in the last " + RECENT_DAYS + " days, against "
                + before.stripTrailingZeros().toPlainString() + "% over the last " + USUAL_DAYS + ".");
    }

    private static java.math.BigDecimal value(
            zelisline.ub.wholesale.api.dto.SupplierStandingResponse standing, String key) {
        if (standing.measures() == null) {
            return null;
        }
        return standing.measures().stream()
                .filter(m -> m != null && key.equals(m.key()))
                .map(zelisline.ub.wholesale.api.dto.SupplierStandingResponse.Measure::value)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** The single most useful thing to do now, or null when nothing is waiting. Never about money. */
    static NextThing nextThing(long waiting, long toDispatch, List<SupplierReadinessResponse.Step> steps) {
        if (waiting > 0) {
            return new NextThing(
                    waiting == 1 ? "1 order is waiting for your answer" : waiting + " orders are waiting for your answer",
                    "/storefront/trade-orders");
        }
        if (toDispatch > 0) {
            return new NextThing(
                    toDispatch == 1 ? "1 accepted order is ready to dispatch" : toDispatch + " accepted orders are ready to dispatch",
                    "/storefront/trade-orders");
        }
        for (SupplierReadinessResponse.Step step : steps) {
            if (!step.done()) {
                return new NextThing(step.label(), step.actionUrl());
            }
        }
        return null;
    }
}
