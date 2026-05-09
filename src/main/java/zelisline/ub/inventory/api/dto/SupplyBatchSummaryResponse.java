package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SupplyBatchSummaryResponse(
        String id,
        String batchNumber,
        String batchName,
        String supplierId,
        String supplierName,
        String branchId,
        Instant receivedAt,
        String status,
        int itemCount,
        BigDecimal totalInitialQuantity,
        BigDecimal totalRemainingQuantity,
        Instant closedAt,
        String closedBy,
        // ── Financial fields ──
        BigDecimal totalCost,
        BigDecimal totalRevenue,
        BigDecimal totalAssociatedCosts,
        int soldPercentage
) {
}
