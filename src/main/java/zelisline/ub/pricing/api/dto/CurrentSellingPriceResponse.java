package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;

/** Resolved open-ended shelf price for POS; omits cost and margin data. */
public record CurrentSellingPriceResponse(BigDecimal price) {
}
