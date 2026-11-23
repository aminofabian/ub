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

    /** Set by Spring; absent in a unit test, where step 5 falls back to the count of sellable products. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SupplierOrderableService orderableService;

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

        // A supplier that came in through the portal has portal users; one that signed up as a shop has none and
        // manages its page from inside the shop, so its steps point there.
        boolean portalUsers = hasPassport
                && !supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(passport.getId()).isEmpty();
        String pageUrl = portalUsers ? "/supplier-portal/claim" : "/storefront/supplier-page";
        String profileUrl = portalUsers ? "/supplier-portal/profile" : "/storefront/supplier-page";
        boolean claimed = hasPassport && passport.isListed();
        boolean paid = hasPassport && hasPayoutDetails(passport);
        boolean delivers = hasPassport && hasDeliveryRegions(passport);
        long sellable = onboardingGateService.snapshot(businessId).sellableSkuCount();
        boolean ordered = salesOrderRepository.existsByBusinessId(businessId);

        List<Step> steps = new ArrayList<>();
        steps.add(new Step("claim_page", "Claim your page", claimed, pageUrl));
        steps.add(new Step("open_shop", "Open your wholesale shop", hasPassport, pageUrl));
        steps.add(new Step("payout", "Say how you are paid", paid,
                portalUsers ? "/supplier-portal/payment-details" : "/storefront/supplier-page"));
        steps.add(new Step("delivery_areas", "Say where you deliver", delivers, profileUrl));
        // Priced, published online and in stock, not merely present. Only checked once there is a product at all.
        boolean canBeOrdered = sellable > 0 && (orderableService == null || orderableService.anyOrderable(businessId));
        steps.add(new Step("first_product", "Have something a buyer can order", canBeOrdered, "/products"));
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

    /**
     * The checklist in the supplier portal (decision D2). The switch is on the supplier's page, because a
     * supplier with no shop has no business to carry a flag. With no shop, the steps that need one are shown as
     * what comes after opening it, never as failures. With a shop, the product and first-order steps are the
     * shop's own facts.
     */
    @Transactional(readOnly = true)
    public zelisline.ub.wholesale.api.dto.PortalReadinessResponse portalReadiness(String marketplaceSupplierId) {
        MarketplaceSupplier passport = marketplaceSupplierRepository.findById(marketplaceSupplierId).orElse(null);
        if (killSwitch || passport == null || !passport.isEngagementEnabled()) {
            return zelisline.ub.wholesale.api.dto.PortalReadinessResponse.off();
        }
        String done = zelisline.ub.wholesale.api.dto.PortalReadinessResponse.DONE;
        String todo = zelisline.ub.wholesale.api.dto.PortalReadinessResponse.TODO;
        String afterShop = zelisline.ub.wholesale.api.dto.PortalReadinessResponse.AFTER_SHOP;
        String businessId = passport.getBusinessId() == null || passport.getBusinessId().isBlank()
                ? null
                : passport.getBusinessId();
        boolean hasShop = businessId != null;
        boolean claimed = passport.isListed()
                && !supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(passport.getId()).isEmpty();

        List<zelisline.ub.wholesale.api.dto.PortalReadinessResponse.Step> steps = new ArrayList<>();
        steps.add(portalStep("claim_page", "Claim your page", claimed ? done : todo, "/supplier-portal/claim"));
        steps.add(portalStep("open_shop", "Open your wholesale shop", hasShop ? done : todo, "/supplier-portal/profile"));
        steps.add(portalStep("payout", "Say how you are paid",
                !hasShop ? afterShop : hasPayoutDetails(passport) ? done : todo, "/supplier-portal/payment-details"));
        steps.add(portalStep("delivery_areas", "Say where you deliver",
                !hasShop ? afterShop : hasDeliveryRegions(passport) ? done : todo, "/supplier-portal/profile"));
        boolean product = hasShop && onboardingGateService.snapshot(businessId).sellableSkuCount() > 0
                && (orderableService == null || orderableService.anyOrderable(businessId));
        steps.add(portalStep("first_product", "Have something a buyer can order",
                !hasShop ? afterShop : product ? done : todo, "/supplier-portal/profile"));
        steps.add(portalStep("first_order", "Take your first order",
                !hasShop ? afterShop : salesOrderRepository.existsByBusinessId(businessId) ? done : todo,
                "/supplier-portal/profile"));
        return new zelisline.ub.wholesale.api.dto.PortalReadinessResponse(true, List.copyOf(steps));
    }

    private static zelisline.ub.wholesale.api.dto.PortalReadinessResponse.Step portalStep(
            String key, String label, String state, String url) {
        return new zelisline.ub.wholesale.api.dto.PortalReadinessResponse.Step(key, label, state, url);
    }
}
