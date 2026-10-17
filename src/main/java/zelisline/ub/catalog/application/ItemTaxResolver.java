package zelisline.ub.catalog.application;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.TaxRateRepository;

/**
 * Resolves the effective tax rate for an item by walking its category chain
 * (leaf first), the same precedence {@code CategoryPricingResolutionService}
 * uses for pricing. Returns {@code null} when no category in the chain sets a
 * default tax rate.
 */
@Service
@RequiredArgsConstructor
public class ItemTaxResolver {

    private static final int MAX_CHAIN_DEPTH = 32;

    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final TaxRateRepository taxRateRepository;

    @Transactional(readOnly = true)
    public ResolvedTax resolve(String businessId, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElse(null);
        if (item == null || item.getCategoryId() == null || item.getCategoryId().isBlank()) {
            return null;
        }

        String categoryId = item.getCategoryId();
        int guard = 0;
        while (categoryId != null && !categoryId.isBlank() && guard++ < MAX_CHAIN_DEPTH) {
            Category category = categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                    .orElse(null);
            if (category == null) {
                return null;
            }
            String taxRateId = category.getDefaultTaxRateId();
            if (taxRateId != null && !taxRateId.isBlank()) {
                return taxRateRepository.findByIdAndBusinessId(taxRateId.trim(), businessId)
                        .filter(TaxRate::isActive)
                        .map(ItemTaxResolver::toResolved)
                        .orElse(null);
            }
            categoryId = category.getParentId();
        }
        return null;
    }

    private static ResolvedTax toResolved(TaxRate taxRate) {
        return new ResolvedTax(
                taxRate.getId(),
                taxRate.getName(),
                taxRate.getRatePercent(),
                taxRate.isInclusive(),
                resolveEtimsCode(taxRate));
    }

    /**
     * The KRA tax code: the shop's explicit {@code etims_tax_code} wins, and the
     * two unambiguous rates are derived as a fallback (B = 16%, E = 8%). Anything
     * else — notably 0%, which could be zero-rated or exempt — is left for the
     * shop to set explicitly rather than guessed.
     */
    static String resolveEtimsCode(TaxRate taxRate) {
        if (taxRate.getEtimsTaxCode() != null && !taxRate.getEtimsTaxCode().isBlank()) {
            return taxRate.getEtimsTaxCode().trim();
        }
        BigDecimal rate = taxRate.getRatePercent();
        if (rate == null) {
            return null;
        }
        if (rate.compareTo(new BigDecimal("16")) == 0) {
            return "B";
        }
        if (rate.compareTo(new BigDecimal("8")) == 0) {
            return "E";
        }
        return null;
    }

    public record ResolvedTax(
            String taxRateId,
            String taxRateName,
            BigDecimal ratePercent,
            boolean inclusive,
            String taxCode
    ) {
    }
}
