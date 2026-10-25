package zelisline.ub.billing.application;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.tenancy.domain.Business;

/**
 * Decides whether a shop has earned a paid subscription yet.
 *
 * <p>New shops start on a trial, but a shop with a free-sized catalogue and no
 * completed sale is still effectively free. It must never receive renewal
 * emails or enter billing grace. Free and demo plans are always excluded.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionBillingEligibilityService {

    static final int DEFAULT_FREE_PRODUCT_LIMIT = 300;

    private final ItemRepository itemRepository;
    private final SaleRepository saleRepository;
    private final SubscriptionBillingSettingsService settingsService;

    public boolean isBillable(Business business) {
        if (business == null || business.getDeletedAt() != null) {
            return false;
        }
        String tier = business.getSubscriptionTier() == null
                ? ""
                : business.getSubscriptionTier().trim().toLowerCase(Locale.ROOT);
        if (tier.isEmpty() || "free".equals(tier) || tier.startsWith("demo")) {
            return false;
        }
        String slug = business.getSlug() == null
                ? ""
                : business.getSlug().trim().toLowerCase(Locale.ROOT);
        if ("demo".equals(slug) || slug.startsWith("demo-")) {
            return false;
        }

        PlatformSubscriptionPlan plan = settingsService.planOrNull(tier);
        if (plan == null) {
            return false;
        }
        BigDecimal price = plan.getMonthlyPriceKes();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        long products = itemRepository
                .countByBusinessIdAndDeletedAtIsNullAndActiveTrue(business.getId());
        if (products > freeProductLimit()) {
            return true;
        }
        return saleRepository.existsByBusinessIdAndStatusAndVoidedAtIsNull(
                business.getId(), SalesConstants.SALE_STATUS_COMPLETED);
    }

    private int freeProductLimit() {
        PlatformSubscriptionPlan free = settingsService.planOrNull("free");
        if (free == null || free.getProductLimit() == null) {
            return DEFAULT_FREE_PRODUCT_LIMIT;
        }
        return free.getProductLimit();
    }
}
