package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record PathAPurchaseOrderLineResponse(
        String id,
        int sortOrder,
        String itemId,
        BigDecimal qtyOrdered,
        BigDecimal qtyReceived,
        BigDecimal unitEstimatedCost,
        String supplierLineStatus,
        BigDecimal qtyAccepted,
        String supplierNote
) {
}
