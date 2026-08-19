package zelisline.ub.discounts.api.dto;

import java.math.BigDecimal;

public record ResolvedPriceResponse(
        BigDecimal regularPrice,
        BigDecimal finalPrice,
        BigDecimal savedAmount,
        ResolvedDiscountRef discount
) {
}
