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

    private final FeatureFlagService featureFlagService;
    private final SupplierMilestoneRepository milestoneRepository;

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
}
