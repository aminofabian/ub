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
        BigDecimal wastageQty
) {
}
