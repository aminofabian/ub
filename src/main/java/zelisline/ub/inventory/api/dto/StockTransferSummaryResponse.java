package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Transfer row for the Inventory → Transfers list. Item names are resolved
 * server-side so the receiving shop can see what's arriving without opening
 * each transfer.
 */
public record StockTransferSummaryResponse(
        String id,
        String status,
        String fromBranchId,
        String toBranchId,
        String notes,
        Instant createdAt,
        String createdBy,
        List<Line> lines
) {

    public record Line(
            String itemId,
            String itemName,
            BigDecimal quantity
    ) {}
}
