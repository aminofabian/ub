package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record SupplierPortalDeliveryRow(
        String purchaseOrderId,
        String businessId,
        String businessName,
        String poNumber,
        LocalDate expectedDate,
        Instant sentToSupplierAt,
        Instant supplierResponseAt,
        Instant updatedAt,
        String deliveryStatus,
        String poStatus,
        BigDecimal qtyOrdered,
        BigDecimal qtyReceived
) {
}
