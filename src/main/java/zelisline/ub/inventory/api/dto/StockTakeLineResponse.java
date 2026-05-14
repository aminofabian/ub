package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record StockTakeLineResponse(
        String id,
        String itemId,
        String itemName,
        String itemSku,
        BigDecimal systemQtySnapshot,
        BigDecimal countedQty,
        BigDecimal adminQuantity,
        String note,
        String aisle,
        String status,
        String submittedBy,
        Instant submittedAt,
        String confirmedBy,
        Instant confirmedAt
) {
}
