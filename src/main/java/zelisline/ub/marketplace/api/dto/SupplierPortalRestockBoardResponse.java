package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SupplierPortalRestockBoardResponse(
        Instant generatedAt,
        String currency,
        String window,
        LocalDate windowStart,
        LocalDate windowEnd,
        int windowDays,
        SupplierPortalRestockBoardSummary summary,
        List<SupplierPortalRestockDayBucket> daily,
        List<SupplierPortalRestockRow> rows,
        int stockShopCount,
        int velocityShopCount,
        int shopCount
) {
    public record SupplierPortalRestockBoardSummary(
            BigDecimal suppliedQty,
            BigDecimal tillQty,
            BigDecimal damageQty,
            BigDecimal onHandQty,
            BigDecimal suggestedQty,
            int needsRestockCount,
            int outOfStockCount
    ) {
    }

    public record SupplierPortalRestockDayBucket(
            LocalDate date,
            BigDecimal suppliedQty,
            BigDecimal tillQty,
            BigDecimal damageQty
    ) {
    }

    public record SupplierPortalRestockRow(
            String key,
            String localSupplierId,
            String shopName,
            String itemId,
            String productName,
            String sku,
            BigDecimal packSize,
            String packUnit,
            BigDecimal suppliedQty,
            BigDecimal tillQty,
            BigDecimal damageQty,
            BigDecimal onHand,
            BigDecimal avgDailyDemand,
            BigDecimal daysOfCover,
            BigDecimal suggestedRestock,
            boolean stockVisible,
            boolean velocityVisible,
            String urgency
    ) {
    }
}
