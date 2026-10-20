package zelisline.ub.billing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.billing.repository.PlatformSubscriptionPlanRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Live catalog, staff seats, and trailing daily sales against the published
 * plan catalogue.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionPlanFitService {

    /** Trading days used to smooth daily sales into a plan-limit figure. */
    public static final int DAILY_REVENUE_WINDOW_DAYS = 30;

    private final BusinessRepository businessRepository;
    private final PlatformSubscriptionPlanRepository planRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final SaleRepository saleRepository;

    /**
     * {@code true} while plan product/seat caps and upgrade notices are
     * deferred (first {@link SubscriptionBillingService#PLAN_LIMIT_EXPERIENCE_DAYS}
     * after the shop was created).
     */
    @Transactional(readOnly = true)
    public boolean isWithinPlanLimitExperience(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return true;
        }
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        if (business == null) {
            return true;
        }
        return SubscriptionBillingService.isWithinPlanLimitExperience(
                business.getCreatedAt(), Instant.now());
    }

    @Transactional(readOnly = true)
    public SubscriptionPlanFit.Result evaluate(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return SubscriptionPlanFit.evaluate(
                    new SubscriptionPlanFit.Usage(0, 0),
                    null,
                    List.of());
        }
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        if (business == null) {
            return SubscriptionPlanFit.evaluate(
                    new SubscriptionPlanFit.Usage(0, 0),
                    null,
                    List.of());
        }
        return evaluate(business);
    }

    @Transactional(readOnly = true)
    public SubscriptionPlanFit.Result evaluate(Business business) {
        SubscriptionPlanFit.Usage usage = usage(business.getId());
        List<SubscriptionPlanFit.PlanSnapshot> plans = activePlans();
        PlatformSubscriptionPlan currentRow = planRepository
                .findById(normalizeTier(business.getSubscriptionTier()))
                .orElse(null);
        return SubscriptionPlanFit.evaluate(usage, toSnapshot(currentRow), plans);
    }

    @Transactional(readOnly = true)
    public SubscriptionPlanFit.Usage usage(String businessId) {
        long products = itemRepository.countByBusinessIdAndDeletedAtIsNullAndActiveTrue(businessId);
        long users = userRepository.countStaffByBusinessIdExcludingBuyers(businessId);
        return new SubscriptionPlanFit.Usage(products, users, averageDailyRevenue(businessId));
    }

    /** Average gross sales per trading day over the trailing window. */
    @Transactional(readOnly = true)
    public BigDecimal averageDailyRevenue(String businessId) {
        Instant until = Instant.now();
        Instant since = until.minus(DAILY_REVENUE_WINDOW_DAYS, ChronoUnit.DAYS);
        List<Object[]> rows = saleRepository.aggregateSalesBetween(businessId, since, until);
        if (rows == null || rows.isEmpty() || rows.get(0) == null || rows.get(0).length < 2) {
            return BigDecimal.ZERO;
        }
        Object rawTotal = rows.get(0)[1];
        if (rawTotal == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = rawTotal instanceof BigDecimal value
                ? value
                : new BigDecimal(rawTotal.toString());
        if (total.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return total.divide(
                BigDecimal.valueOf(DAILY_REVENUE_WINDOW_DAYS),
                2,
                RoundingMode.HALF_UP);
    }

    /** Does this published package cover the shop right now? */
    @Transactional(readOnly = true)
    public boolean planCovers(PlatformSubscriptionPlan plan, SubscriptionPlanFit.Usage usage) {
        return SubscriptionPlanFit.fits(usage, toSnapshot(plan));
    }

    @Transactional(readOnly = true)
    public List<SubscriptionBillingDtos.PlanResponse> activePlanResponses() {
        return planRepository.findAllByActiveTrueOrderBySortOrderAsc().stream()
                .map(this::toPlanResponse)
                .toList();
    }

    public SubscriptionBillingDtos.PlanFitView toView(SubscriptionPlanFit.Result result) {
        return toView(result, true);
    }

    /**
     * @param enforceLimits when {@code false} (experience window), usage and
     *                      published caps stay visible but upgrade / over-limit
     *                      flags stay quiet so the dashboard never nags.
     */
    public SubscriptionBillingDtos.PlanFitView toView(
            SubscriptionPlanFit.Result result,
            boolean enforceLimits
    ) {
        SubscriptionPlanFit.PlanSnapshot current = result.current();
        SubscriptionPlanFit.PlanSnapshot recommended = result.recommended();
        boolean overProduct = enforceLimits && result.overProductLimit();
        boolean overUser = enforceLimits && result.overUserLimit();
        boolean overRevenue = enforceLimits && result.overDailyRevenueLimit();
        boolean needsUpgrade = enforceLimits && result.needsUpgrade();
        boolean negotiable = enforceLimits && result.negotiable();
        boolean talkToUs = enforceLimits && result.talkToUs();
        return new SubscriptionBillingDtos.PlanFitView(
                result.usage().productCount(),
                result.usage().userCount(),
                current != null ? current.productLimit() : null,
                current != null ? current.cashierLimit() : null,
                result.usage().dailyRevenueKes(),
                current != null ? current.dailyRevenueLimitKes() : null,
                overProduct,
                overUser,
                overRevenue,
                needsUpgrade,
                negotiable,
                talkToUs,
                enforceLimits && recommended != null ? recommended.tierCode() : null,
                enforceLimits && recommended != null ? recommended.displayName() : null,
                enforceLimits && recommended != null ? recommended.monthlyPriceKes() : null,
                enforceLimits ? result.reasons() : List.of());
    }

    private List<SubscriptionPlanFit.PlanSnapshot> activePlans() {
        return planRepository.findAllByActiveTrueOrderBySortOrderAsc().stream()
                .map(this::toSnapshot)
                .toList();
    }

    private SubscriptionPlanFit.PlanSnapshot toSnapshot(PlatformSubscriptionPlan row) {
        if (row == null) {
            return null;
        }
        return new SubscriptionPlanFit.PlanSnapshot(
                row.getTierCode(),
                row.getDisplayName(),
                row.getProductLimit(),
                row.getCashierLimit(),
                row.getSortOrder(),
                row.getMonthlyPriceKes(),
                row.getDailyRevenueLimitKes());
    }

    private SubscriptionBillingDtos.PlanResponse toPlanResponse(PlatformSubscriptionPlan row) {
        return new SubscriptionBillingDtos.PlanResponse(
                row.getTierCode(),
                row.getDisplayName(),
                row.getMonthlyPriceKes(),
                row.getAnnualPriceKes(),
                row.getGraceDays(),
                row.getProductLimit(),
                row.getCashierLimit(),
                row.getDailyRevenueLimitKes(),
                row.isActive(),
                row.getSortOrder());
    }

    private static String normalizeTier(String tier) {
        return tier == null ? "" : tier.trim().toLowerCase();
    }
}
