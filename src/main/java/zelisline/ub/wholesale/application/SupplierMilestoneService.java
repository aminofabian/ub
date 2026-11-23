package zelisline.ub.wholesale.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.wholesale.domain.SupplierMilestone;
import zelisline.ub.wholesale.repository.SupplierMilestoneRepository;

/**
 * Records supplier milestones once each. Off unless the milestones flag is on, and off everywhere when the
 * global kill switch {@code supplier-engagement.kill-switch} is set.
 */
@Service
@RequiredArgsConstructor
public class SupplierMilestoneService {

    public static final String FIRST_ORDER_SUPPLIED = "first_order_supplied";
    public static final String FIRST_ORDER_CONFIRMED_IN_FULL = "first_order_confirmed_in_full";
    public static final String FIRST_REPEAT_BUYER = "first_repeat_buyer";
    public static final String TENTH_ORDER_SUPPLIED = "tenth_order_supplied";
    public static final String HUNDREDTH_ORDER_SUPPLIED = "hundredth_order_supplied";
    public static final String ONE_YEAR_ON_KIOSK = "one_year_on_kiosk";

    /** What a milestone is called on the overview. */
    public static String label(String key) {
        if (key == null) {
            return "";
        }
        return switch (key) {
            case FIRST_ORDER_SUPPLIED -> "First order supplied";
            case FIRST_ORDER_CONFIRMED_IN_FULL -> "First order the buyer confirmed in full";
            case FIRST_REPEAT_BUYER -> "First repeat buyer";
            case TENTH_ORDER_SUPPLIED -> "10 orders supplied";
            case HUNDREDTH_ORDER_SUPPLIED -> "100 orders supplied";
            case ONE_YEAR_ON_KIOSK -> "One year on Kiosk";
            default -> key;
        };
    }

    private final FeatureFlagService featureFlagService;
    private final SupplierMilestoneRepository milestoneRepository;
    private final zelisline.ub.wholesale.repository.SalesOrderRepository salesOrderRepository;
    private final zelisline.ub.trade.repository.TradeOrderRepository tradeOrderRepository;
    private final zelisline.ub.tenancy.repository.BusinessRepository businessRepository;

    @Value("${supplier-engagement.kill-switch:false}")
    private boolean killSwitch;

    /**
     * Called when a shop supplies its first buyer. Recorded at most once per business. The check and the
     * insert are not atomic; two first supplies at the same instant could both insert, and the unique key
     * then rejects the second, which is rare and harmless (the milestone exists either way).
     */
    @Transactional
    public void recordFirstOrderSupplied(String businessId) {
        if (killSwitch
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_MILESTONES)) {
            return;
        }
        if (milestoneRepository.existsByBusinessIdAndMilestoneKey(businessId, FIRST_ORDER_SUPPLIED)) {
            return;
        }
        milestoneRepository.save(new SupplierMilestone(businessId, FIRST_ORDER_SUPPLIED));
    }

    /**
     * Records every milestone that has become true, once each. Called when the overview is read, never when an
     * order is answered, dispatched or received, so the order path stays as it was. The recorded time is when
     * the milestone was noticed, which can be later than when it became true.
     */
    @Transactional
    public void recordDue(String businessId) {
        if (killSwitch
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_MILESTONES)) {
            return;
        }
        long supplied = salesOrderRepository.countSupplied(businessId);
        recordIf(businessId, FIRST_ORDER_SUPPLIED, supplied >= 1);
        recordIf(businessId, TENTH_ORDER_SUPPLIED, supplied >= 10);
        recordIf(businessId, HUNDREDTH_ORDER_SUPPLIED, supplied >= 100);
        if (!milestoneRepository.existsByBusinessIdAndMilestoneKey(businessId, FIRST_REPEAT_BUYER)) {
            recordIf(businessId, FIRST_REPEAT_BUYER,
                    !salesOrderRepository.findRepeatBuyerBusinessIds(businessId).isEmpty());
        }
        if (!milestoneRepository.existsByBusinessIdAndMilestoneKey(businessId, FIRST_ORDER_CONFIRMED_IN_FULL)) {
            recordIf(businessId, FIRST_ORDER_CONFIRMED_IN_FULL,
                    tradeOrderRepository.countConfirmedInFull(businessId) > 0);
        }
        if (!milestoneRepository.existsByBusinessIdAndMilestoneKey(businessId, ONE_YEAR_ON_KIOSK)) {
            recordIf(businessId, ONE_YEAR_ON_KIOSK, businessRepository.findById(businessId)
                    .map(b -> b.getCreatedAt() != null && b.getCreatedAt()
                            .isBefore(java.time.Instant.now().minus(365, java.time.temporal.ChronoUnit.DAYS)))
                    .orElse(false));
        }
    }

    private void recordIf(String businessId, String key, boolean reached) {
        if (reached && !milestoneRepository.existsByBusinessIdAndMilestoneKey(businessId, key)) {
            milestoneRepository.save(new SupplierMilestone(businessId, key));
        }
    }

    /** The supplier dismissed the milestone notes: each is shown once. */
    @Transactional
    public void markAllSeen(String businessId) {
        java.time.Instant now = java.time.Instant.now();
        for (SupplierMilestone milestone : milestoneRepository.findByBusinessIdOrderByAchievedAtAsc(businessId)) {
            if (milestone.getSeenAt() == null) {
                milestone.setSeenAt(now);
                milestoneRepository.save(milestone);
            }
        }
    }
}
