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
        String supplierNote,
        /** Packaging as ordered (null for lines created before packaging was recorded). */
        String packOptionId,
        String purchaseUnit,
        BigDecimal purchaseQty,
        BigDecimal unitsPerPurchaseUnit,
        BigDecimal packPrice,
        String baseUnit
) {
}
