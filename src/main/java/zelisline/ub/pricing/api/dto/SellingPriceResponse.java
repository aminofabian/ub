package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SellingPriceResponse(
        String id,
        String itemId,
        String branchId,
        BigDecimal price,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {
}
