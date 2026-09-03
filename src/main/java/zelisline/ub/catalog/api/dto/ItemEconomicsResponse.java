package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ItemEconomicsResponse(
        String itemId,
        String name,
        boolean includesVariants,
        int skuCount,
        BigDecimal unitsSold,
        BigDecimal unitsSold7d,
        BigDecimal unitsSold30d,
        BigDecimal revenue,
        BigDecimal costOfGoods,
        BigDecimal grossProfit,
        long saleCount,
        Instant lastSoldAt,
        BigDecimal supplierSpend,
        BigDecimal unitsBought,
        BigDecimal onHand,
        List<ItemEconomicsDayPoint> last30Days,
        List<ItemSupplierSpendRow> supplierSpendBreakdown,
        List<ItemSaleHistoryRow> sales,
        List<ItemPurchaseHistoryRow> purchases
) {
}
