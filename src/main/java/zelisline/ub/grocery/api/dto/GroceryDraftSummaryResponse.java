package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record GroceryDraftSummaryResponse(
        String id,
        long counterNumber,
        String status,
        String branchId,
        int lineCount,
        BigDecimal grandTotal,
        String currency,
        String createdBy,
        String createdByName,
        Instant createdAt,
        Instant updatedAt
) {
}
