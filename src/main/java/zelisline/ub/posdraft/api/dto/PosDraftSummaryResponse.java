package zelisline.ub.posdraft.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PosDraftSummaryResponse(
        String id,
        long ticketNumber,
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
