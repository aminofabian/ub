package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SupplierPortalOrderDetailResponse(
        String purchaseOrderId,
        String businessId,
        String businessName,
        String poNumber,
        LocalDate expectedDate,
        String status,
        String notes,
        Instant sentToSupplierAt,
        Instant supplierResponseAt,
        String deliveryStatus,
        List<SupplierPortalOrderLineResponse> lines
) {
    public record SupplierPortalOrderLineResponse(
            String lineId,
            String itemId,
            String itemName,
            String itemSku,
            BigDecimal qtyOrdered,
            BigDecimal qtyReceived,
            BigDecimal unitEstimatedCost,
            String supplierLineStatus,
            BigDecimal qtyAccepted,
            String supplierNote
    ) {
    }
}
