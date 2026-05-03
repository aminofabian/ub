package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record SaleItemResponse(
        String id,
        int lineIndex,
        String itemId,
        String batchId,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        BigDecimal unitCost,
        BigDecimal costTotal,
        BigDecimal profit
) {
}
