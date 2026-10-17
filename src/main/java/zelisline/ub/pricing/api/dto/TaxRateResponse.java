package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;

public record TaxRateResponse(
        String id,
        String name,
        BigDecimal ratePercent,
        boolean inclusive,
        boolean active,
        /** KRA eTIMS tax code (A/B/C/D/E); null when the shop has not set one. */
        String etimsTaxCode
) {
}
