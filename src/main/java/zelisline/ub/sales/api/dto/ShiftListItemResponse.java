package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Compact shift record for the list view (Column 1).
 */
public record ShiftListItemResponse(
        String id,
        String branchId,
        String branchName,
        String status,
        String cashierName,
        String cashierId,
        Instant openedAt,
        Instant closedAt,
        BigDecimal openingFloat,
        BigDecimal actualCountedCash,
        BigDecimal expectedCash,
        BigDecimal variance,
        int transactionCount,
        BigDecimal totalSales,
        String registerName,
        String shiftNumber,
        BigDecimal cashTakenOut,
        /** True for the hidden shift the system keeps for a till while the shop has shifts switched off. */
        boolean autoManaged
) {

    /**
     * Nested record for per-shift sales breakdown.
     */
    public record SalesSummary(
            String paymentMethod,
            BigDecimal grossSales,
            BigDecimal discounts,
            BigDecimal refunds,
            BigDecimal netSales,
            int transactionCount
    ) {
    }
}
