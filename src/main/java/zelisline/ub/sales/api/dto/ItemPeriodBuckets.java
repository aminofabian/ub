package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

/** Fixed business-day sold buckets for one item. */
public record ItemPeriodBuckets(
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
