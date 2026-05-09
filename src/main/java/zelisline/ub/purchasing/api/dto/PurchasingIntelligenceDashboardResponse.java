package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record PurchasingIntelligenceDashboardResponse(
        Summary summary,
        List<SpendTrendPoint> spendTrend,
        List<SupplierSpendPoint> topSuppliers,
        List<CategorySpendPoint> topCategories,
        List<PriceVarianceAlert> priceAlerts,
        List<SingleSourceRiskRow> singleSourceRisks,
        List<Insight> insights
) {

    public record Summary(
            BigDecimal totalSpend,
            int supplierCount,
            int invoiceLineCount,
            int itemCount,
            BigDecimal avgVariancePercent,
            int abovePrimaryCount,
            int belowPrimaryCount,
            int singleSourceRiskCount
    ) {
    }

    public record SpendTrendPoint(
            String date,
            BigDecimal spend
    ) {
    }

    public record SupplierSpendPoint(
            String supplierId,
            String supplierName,
            BigDecimal spend,
            int lineCount
    ) {
    }

    public record CategorySpendPoint(
            String categoryId,
            String categoryName,
            BigDecimal spend,
            int lineCount
    ) {
    }

    public record PriceVarianceAlert(
            String itemId,
            String itemSku,
            String invoiceId,
            BigDecimal paidUnitCost,
            BigDecimal primaryLastCost,
            BigDecimal variancePercent,
            boolean fromPrimarySupplier
    ) {
    }

    public record Insight(
            String kind,
            String message
    ) {
    }
}
