package zelisline.ub.ai.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI review of one product. Every suggestion is optional: the backend only fills
 * fields the model clearly wants to change, and id-based fields are validated
 * against the business's real categories / departments before being returned.
 */
public record ProductPolishResponse(
        String requestId,
        String summary,
        List<String> issues,
        String suggestedName,
        String suggestedBrand,
        String suggestedSize,
        String suggestedDescription,
        /** Validated real category id (null when no change suggested). */
        String categoryId,
        String categoryName,
        String categoryReason,
        /** Validated real department (item type) id (null when no change suggested). */
        String itemTypeId,
        String itemTypeName,
        String itemTypeReason,
        BigDecimal suggestedSellPrice,
        BigDecimal suggestedCostPrice,
        String pricingReason,
        BigDecimal suggestedMinStock,
        BigDecimal suggestedReorderLevel,
        BigDecimal suggestedReorderQty,
        String stockReason
) {
}
