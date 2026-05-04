package zelisline.ub.reporting.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/** Report #8 — expiry pipeline buckets (Phase 7 Slice 4). OLTP on active batches. */
public record InventoryExpiryPipelineResponse(
        String branchId,
        Map<String, Bucket> buckets
) {

    public record Bucket(long batchCount, BigDecimal qtyRemaining) {
    }
}
