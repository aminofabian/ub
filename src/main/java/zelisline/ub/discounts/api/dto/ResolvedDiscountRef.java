package zelisline.ub.discounts.api.dto;

import java.math.BigDecimal;

public record ResolvedDiscountRef(
        String id,
        String name,
        String method,
        BigDecimal value,
        String scope
) {
}
