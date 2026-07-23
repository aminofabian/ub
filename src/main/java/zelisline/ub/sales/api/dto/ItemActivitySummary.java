package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ItemActivitySummary(
        String itemId,
        String itemName,
        String sku,
        BigDecimal currentStock,
        Instant lastReceiptAt,
        BigDecimal lastReceiptQty,
        BigDecimal soldSinceLastReceipt,
        BigDecimal sellThroughPct,
        BigDecimal avgUnitsPerDay7d
) {
}
