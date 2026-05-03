package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

public record StockAdjustmentRequestResponse(
        String id,
        String stockTakeLineId,
        String itemId,
        BigDecimal varianceQty,
        BigDecimal systemQtySnapshot,
        BigDecimal countedQty,
        String status
) {
}
