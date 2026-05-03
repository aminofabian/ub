package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;

public record TaxRateResponse(
        String id,
        String name,
        BigDecimal ratePercent,
        boolean inclusive
) {
}
