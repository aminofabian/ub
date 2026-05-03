package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record StockTakeSessionResponse(
        String id,
        String branchId,
        String status,
        String notes,
        Instant closedAt,
        List<StockTakeLineResponse> lines,
        List<StockAdjustmentRequestResponse> adjustmentRequests
) {
}
