package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record StaffPerformanceRow(
        String userId,
        String userName,
        long saleCount,
        long itemCount,
        BigDecimal totalRevenue,
        BigDecimal totalProfit
) {
}
