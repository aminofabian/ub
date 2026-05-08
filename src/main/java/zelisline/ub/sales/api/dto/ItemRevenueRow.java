package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record ItemRevenueRow(
        String itemId,
        String itemName,
        String sku,
        BigDecimal quantitySold,
        BigDecimal grossRevenue,
        BigDecimal refundAmount,
        BigDecimal netRevenue,
        BigDecimal netProfit
) {}
