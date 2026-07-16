package zelisline.ub.inventory.api.dto;

import java.util.List;

/**
 * Result of a bulk margin→cost adjustment. {@code updated} rows reflect post-adjust state;
 * {@code skipped} lists items that could not be adjusted (e.g. missing sell price).
 */
public record BulkAdjustItemCostResponse(
        List<CostIssueRowResponse> updated,
        List<SkippedItem> skipped
) {
    public record SkippedItem(String itemId, String reason) {
    }
}
