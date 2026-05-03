package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;

public record SellPriceSuggestionResponse(
        BigDecimal latestUnitCost,
        BigDecimal marginPercent,
        String ruleName,
        BigDecimal suggestedSellPrice,
        String note
) {
}
