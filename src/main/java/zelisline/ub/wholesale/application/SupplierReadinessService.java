package zelisline.ub.wholesale.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.onboarding.sequence.application.MerchantOnboardingGateService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.wholesale.api.dto.SupplierReadinessResponse;
import zelisline.ub.wholesale.api.dto.SupplierReadinessResponse.Step;
import zelisline.ub.wholesale.repository.SalesOrderRepository;
import zelisline.ub.wholesale.repository.SupplierMilestoneRepository;

/**
 * The six readiness steps for a supplier's shop (SUPPLIER_ENGAGEMENT_DESIGN.md §4.1). Each step is a
 * fact read from the database. Nothing is stored, so recomputing always gives the same answer.
 */
@Service
@RequiredArgsConstructor
public class SupplierReadinessService {

    private final FeatureFlagService featureFlagService;
    private final MerchantOnboardingGateService onboardingGateService;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SupplierMilestoneRepository milestoneRepository;

    @Value("${supplier-engagement.kill-switch:false}")
    private boolean killSwitch;

    @Transactional(readOnly = true)
    public SupplierReadinessResponse readiness(String businessId) {
        if (killSwitch
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_READINESS)) {
            return SupplierReadinessResponse.off();
        }
        MarketplaceSupplier passport = marketplaceSupplierRepository.findFirstByBusinessId(businessId).orElse(null);
        boolean hasPassport = passport != null;

        boolean claimed = hasPassport && passport.isListed()
                && !supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(passport.getId()).isEmpty();
        boolean paid = hasPassport && hasPayoutDetails(passport);
        boolean delivers = hasPassport && hasDeliveryRegions(passport);
        long sellable = onboardingGateService.snapshot(businessId).sellableSkuCount();
        boolean ordered = salesOrderRepository.existsByBusinessId(businessId);

        List<Step> steps = new ArrayList<>();
        steps.add(new Step("claim_page", "Claim your page", claimed, "/supplier-portal/claim"));
        steps.add(new Step("open_shop", "Open your wholesale shop", hasPassport, "/supplier-portal/claim"));
        steps.add(new Step("payout", "Say how you are paid", paid, "/supplier-portal/payment-details"));
        steps.add(new Step("delivery_areas", "Say where you deliver", delivers, "/supplier-portal/profile"));
        steps.add(new Step("first_product", "Have something a buyer can order", sellable > 0, "/products"));
        steps.add(new Step("first_order", "Take your first order", ordered, "/storefront/trade-orders"));
        List<SupplierReadinessResponse.Milestone> milestones = milestoneRepository
                .findByBusinessIdOrderByAchievedAtAsc(businessId).stream()
                .map(m -> new SupplierReadinessResponse.Milestone(m.getMilestoneKey(), m.getAchievedAt()))
                .toList();
        return new SupplierReadinessResponse(true, steps, milestones);
    }

    /** The payout details the passport already holds: a paybill, a till, mobile money, or a complete bank account. */
    private static boolean hasPayoutDetails(MarketplaceSupplier p) {
        return notBlank(p.getPaybill()) || notBlank(p.getTillNumber()) || notBlank(p.getMobileMoney())
                || (notBlank(p.getBankName()) && notBlank(p.getBankAccountNumber()));
    }

    private static boolean hasDeliveryRegions(MarketplaceSupplier p) {
        String json = p.getDeliveryRegionsJson();
        return notBlank(json) && !"[]".equals(json.trim());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
