package zelisline.ub.marketplace.api.dto;

import java.time.Instant;
import java.time.LocalDate;

public record SupplierPortalOrderListRow(
        String purchaseOrderId,
        String businessId,
        String businessName,
        String poNumber,
        LocalDate expectedDate,
        String status,
        Instant sentToSupplierAt,
        Instant supplierResponseAt,
        String deliveryStatus,
        int lineCount
) {
}
