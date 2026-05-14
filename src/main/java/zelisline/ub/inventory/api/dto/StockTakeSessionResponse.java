package zelisline.ub.inventory.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record StockTakeSessionResponse(
        String id,
        int sessionNumber,
        String branchId,
        String status,
        String sessionType,
        LocalDate sessionDate,
        String name,
        String notes,
        String startedBy,
        Instant closedAt,
        String closedBy,
        List<StockTakeLineResponse> lines,
        List<StockAdjustmentRequestResponse> adjustmentRequests
) {
}
