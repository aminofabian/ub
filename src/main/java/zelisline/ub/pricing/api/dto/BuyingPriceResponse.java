package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BuyingPriceResponse(
        String id,
        String itemId,
        String supplierId,
        BigDecimal unitCost,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {
}
