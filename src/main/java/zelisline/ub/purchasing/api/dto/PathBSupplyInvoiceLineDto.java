package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record PathBSupplyInvoiceLineDto(
        String id,
        String description,
        String itemId,
        BigDecimal qty,
        BigDecimal unitCost,
        BigDecimal lineTotal,
        int sortOrder,
        BigDecimal usableQty,
        BigDecimal wastageQty,
        /** Packaging snapshot captured when the receipt was posted (null for legacy lines). */
        String purchaseUnit,
        BigDecimal purchaseQty,
        BigDecimal unitsPerPurchaseUnit,
        BigDecimal packPrice,
        String baseUnit
) {
}
