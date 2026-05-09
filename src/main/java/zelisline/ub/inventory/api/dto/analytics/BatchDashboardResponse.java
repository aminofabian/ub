package zelisline.ub.inventory.api.dto.analytics;

import java.math.BigDecimal;
import java.util.List;

public record BatchDashboardResponse(
        BatchSummaryCards summary,
        List<BatchTrendPoint> dailyTrend,
        List<StatusDistributionPoint> statusDistribution,
        List<TopProductPoint> topProducts,
        List<ExpiringBatchPoint> expiringBatches,
        List<LowStockProductPoint> lowStockProducts,
        List<BatchAlert> alerts
) {

    public record BatchSummaryCards(
            long totalBatchesToday,
            long totalBatchesThisWeek,
            long totalBatchesThisMonth,
            long activeBatches,
            long completedBatches,
            long zeroQuantityBatches,
            long lowQuantityBatches,
            long expiredBatches,
            BigDecimal totalUnitsProduced,
            BigDecimal totalProductionCost,
            BigDecimal estimatedStockValue,
            BigDecimal averageQuantityPerBatch
    ) {
    }

    public record BatchTrendPoint(
            String date,
            long batchesCreated,
            BigDecimal quantityProduced,
            BigDecimal productionCost
    ) {
    }

    public record StatusDistributionPoint(
            String status,
            long count
    ) {
    }

    public record TopProductPoint(
            String itemId,
            String itemName,
            String itemSku,
            long batchCount,
            BigDecimal totalQuantity,
            BigDecimal totalValue
    ) {
    }

    public record ExpiringBatchPoint(
            String batchId,
            String supplyBatchId,
            String batchNumber,
            String itemId,
            String itemName,
            BigDecimal quantityRemaining,
            String expiryDate,
            int daysUntilExpiry
    ) {
    }

    public record LowStockProductPoint(
            String itemId,
            String itemName,
            String itemSku,
            BigDecimal currentStock,
            BigDecimal reorderLevel,
            String categoryName
    ) {
    }

    public record BatchAlert(
            String kind,
            String message,
            Long count
    ) {
    }
}
