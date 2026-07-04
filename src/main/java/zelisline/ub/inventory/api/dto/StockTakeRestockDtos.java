package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class StockTakeRestockDtos {

    private StockTakeRestockDtos() {
    }

    public record StockTakeRestockSupplierOption(
            String supplierId,
            String supplierName,
            boolean primary,
            BigDecimal defaultCostPrice,
            BigDecimal lastCostPrice,
            BigDecimal buyingPrice,
            BigDecimal packSize,
            String packUnit,
            Instant lastPurchaseAt
    ) {}

    public record StockTakeRestockSupplierOptionsResponse(
            List<StockTakeRestockSupplierOption> options,
            StockTakeRestockItemResponse pendingSuggestion
    ) {}

    public record StockTakeRestockItemResponse(
            String id,
            String businessId,
            String branchId,
            String dailyAuditId,
            String stockTakeSessionId,
            String stockTakeLineId,
            String itemId,
            String itemName,
            String itemSku,
            String supplierId,
            String supplierName,
            BigDecimal suggestedQty,
            BigDecimal buyingPrice,
            BigDecimal supplierPackSize,
            String supplierPackUnit,
            BigDecimal lineTotal,
            String addedById,
            String addedByName,
            Instant addedAt,
            String notes,
            String status,
            String rejectionReason,
            String reviewedBy,
            Instant reviewedAt,
            String purchaseOrderId,
            String orderNumber,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record StockTakeRestockSupplierGroup(
            String supplierId,
            String supplierName,
            String supplierPhone,
            String supplierEmail,
            String supplierLocation,
            List<StockTakeRestockItemResponse> items,
            BigDecimal supplierSubtotal
    ) {}

    public record StockTakeRestockReviewResponse(
            String branchId,
            String dailyAuditId,
            LocalDate auditDate,
            String status,
            List<StockTakeRestockSupplierGroup> groups
    ) {}

    public record GenerateRestockOrderResponse(
            List<RestockOrderSummary> orders
    ) {}

    public record RestockOrderSummary(
            String orderNumber,
            String supplierId,
            String supplierName,
            int itemCount,
            BigDecimal supplierSubtotal,
            String status,
            Instant orderDraftedAt
    ) {}
}
