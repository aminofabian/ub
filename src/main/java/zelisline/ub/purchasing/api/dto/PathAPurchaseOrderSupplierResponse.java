package zelisline.ub.purchasing.api.dto;

import java.time.Instant;

public record PathAPurchaseOrderSupplierResponse(
        String purchaseOrderId,
        Instant sentToSupplierAt,
        Instant supplierResponseAt,
        String deliveryStatus,
        PathAPurchaseOrderDetailResponse order
) {
}
