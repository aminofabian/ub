package zelisline.ub.purchasing.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PathAPurchaseOrderDetailResponse(
        String id,
        String supplierId,
        String branchId,
        String poNumber,
        LocalDate expectedDate,
        String status,
        String notes,
        String source,
        Instant sentToSupplierAt,
        Instant supplierResponseAt,
        String deliveryStatus,
        List<PathAPurchaseOrderLineResponse> lines
) {
}
