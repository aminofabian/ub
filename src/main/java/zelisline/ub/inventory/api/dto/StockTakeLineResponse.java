package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

public record StockTakeLineResponse(
        String id,
        String itemId,
        BigDecimal systemQtySnapshot,
        BigDecimal countedQty,
        String note
) {
}
