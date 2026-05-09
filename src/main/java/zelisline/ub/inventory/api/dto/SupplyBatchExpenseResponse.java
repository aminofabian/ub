package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SupplyBatchExpenseResponse(
        String id,
        String supplyBatchId,
        String category,
        BigDecimal amount,
        String description,
        Instant createdAt,
        String createdBy
) {
}
