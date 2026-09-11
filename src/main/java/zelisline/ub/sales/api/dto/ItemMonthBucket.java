package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record ItemMonthBucket(
        int year,
        int month,
        String label,
        BigDecimal qty,
        boolean peak
) {
}
