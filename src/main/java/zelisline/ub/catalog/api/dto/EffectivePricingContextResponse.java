package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record EffectivePricingContextResponse(
        String itemId,
        String categoryId,
        String categoryName,
        BigDecimal inheritedMarkupPercent,
        /** Machine-readable source key, e.g. {@code category:<uuid>} or {@code none}. */
        String markupSourceKey,
        String resolvedTaxRateId,
        String resolvedTaxRateName,
        BigDecimal resolvedTaxRatePercent,
        Boolean taxInclusive,
        String taxSourceKey,
        List<LinkedPriceRuleRef> linkedPriceRules
) {
}
