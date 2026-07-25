package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;

public record GlobalSupplierHubTotals(
        BigDecimal owed,
        BigDecimal paid,
        BigDecimal pending
) {
}
