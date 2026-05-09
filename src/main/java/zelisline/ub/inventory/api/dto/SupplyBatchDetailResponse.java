package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SupplyBatchDetailResponse(
        String id,
        String batchNumber,
        String batchName,
        String supplierId,
        String supplierName,
        String branchId,
        Instant receivedAt,
        String status,
        String sourceType,
        int itemCount,
        BigDecimal totalInitialQuantity,
        BigDecimal totalRemainingQuantity,
        Instant closedAt,
        String closedBy,
        List<SupplyBatchItemResponse> items,
        // ── Financial fields ──
        BigDecimal totalCost,
        BigDecimal totalRevenue,
        BigDecimal totalAssociatedCosts,
        int soldPercentage,
        List<SupplyBatchExpenseResponse> expenses
) {
}
