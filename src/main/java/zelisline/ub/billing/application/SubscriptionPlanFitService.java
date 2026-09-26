package zelisline.ub.billing.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.billing.repository.PlatformSubscriptionPlanRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Live catalog + staff seats against the published plan catalogue.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionPlanFitService {

    private final BusinessRepository businessRepository;
    private final PlatformSubscriptionPlanRepository planRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;

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
        return new SubscriptionPlanFit.Usage(products, users);
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
        boolean needsUpgrade = enforceLimits && result.needsUpgrade();
        boolean negotiable = enforceLimits && result.negotiable();
        boolean talkToUs = enforceLimits && result.talkToUs();
        return new SubscriptionBillingDtos.PlanFitView(
                result.usage().productCount(),
                result.usage().userCount(),
                current != null ? current.productLimit() : null,
                current != null ? current.cashierLimit() : null,
                overProduct,
                overUser,
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
                row.getMonthlyPriceKes());
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
                row.isActive(),
                row.getSortOrder());
    }

    private static String normalizeTier(String tier) {
        return tier == null ? "" : tier.trim().toLowerCase();
    }
}
