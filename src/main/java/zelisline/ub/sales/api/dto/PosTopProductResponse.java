package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row in the POS "Top sellers" panel. Aggregated server-side from
 * completed, non-voided sales at the branch, ranked by units sold.
 */
public record PosTopProductResponse(
        String id,
        String name,
        String sku,
        String thumbnailUrl,
        /** Distinct completed sale count this item appears on. */
        long saleCount,
        /** Sum of quantities sold across considered sales. */
        BigDecimal totalQuantity,
        /** {@code sold_at} of the most recent sale this item appears on. */
        Instant lastSoldAt
) {
}
