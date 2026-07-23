package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ItemStockInRow(
        String id,
        String movementType,
        BigDecimal quantityDelta,
        String branchId,
        String reason,
        String notes,
        Instant createdAt
) {
}
