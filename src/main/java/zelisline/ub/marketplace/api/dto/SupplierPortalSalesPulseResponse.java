package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SupplierPortalSalesPulseResponse(
        Instant generatedAt,
        String currency,
        SupplierPortalSalesPulseSummary summary,
        List<SupplierPortalSalesPulseProduct> products,
        List<SupplierPortalSalesPulseEvent> events,
        int velocityShopCount,
        int shopCount
) {
    public record SupplierPortalSalesPulseSummary(
            BigDecimal supplyQtyToday,
            BigDecimal supplyAmountToday,
            BigDecimal tillQtyToday,
            BigDecimal supplyQty7d,
            BigDecimal tillQty7d,
            int eventCount
    ) {
    }

    public record SupplierPortalSalesPulseProduct(
            String key,
            String productName,
            String shopName,
            String channel,
            BigDecimal qtyToday,
            BigDecimal qty7d,
            BigDecimal amountToday,
            BigDecimal amount7d
    ) {
    }

    public record SupplierPortalSalesPulseEvent(
            String id,
            Instant at,
            String channel,
            String productName,
            String shopName,
            BigDecimal quantity,
            BigDecimal amount
    ) {
    }
}
