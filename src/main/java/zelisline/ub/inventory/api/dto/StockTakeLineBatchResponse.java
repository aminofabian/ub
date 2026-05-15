package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StockTakeLineBatchResponse(
        String id,
        String batchId,
        String batchNumber,
        LocalDate expiryDate,
        BigDecimal systemQtySnapshot,
        BigDecimal countedQty,
        int sortOrder
) {
}
