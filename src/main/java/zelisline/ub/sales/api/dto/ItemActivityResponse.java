package zelisline.ub.sales.api.dto;

import java.util.List;

public record ItemActivityResponse(
        ItemActivitySummary summary,
        ItemPeriodBuckets periods,
        List<ItemDailySalesRow> daily,
        List<ItemStockInRow> stockIns,
        List<RecentSaleRow> recentSales
) {
}
