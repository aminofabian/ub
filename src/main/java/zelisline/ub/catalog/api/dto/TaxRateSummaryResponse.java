package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

public record TaxRateSummaryResponse(
        String id,
        String name,
        BigDecimal ratePercent,
        boolean inclusive
) {
}
