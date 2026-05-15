package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

public record ReconciliationLine(
        String itemId,
        String itemName,
        String sku,
        BigDecimal openingStock,
        BigDecimal unitsSold,
        BigDecimal expectedClosing,
        BigDecimal actualClosing,
        BigDecimal variance,
        String missingIn
) {
}
