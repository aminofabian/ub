package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

/** Multi-period sold qty/revenue for one SKU (today … 30d). */
public record ItemVelocityRow(
        String itemId,
        String itemName,
        String sku,
        BigDecimal currentStock,
        BigDecimal todayQty,
        BigDecimal todayRevenue,
        BigDecimal yesterdayQty,
        BigDecimal yesterdayRevenue,
        BigDecimal last3Qty,
        BigDecimal last3Revenue,
        BigDecimal last7Qty,
        BigDecimal last7Revenue,
        BigDecimal last30Qty,
        BigDecimal last30Revenue
) {
}
