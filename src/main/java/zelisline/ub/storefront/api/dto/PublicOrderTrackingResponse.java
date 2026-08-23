package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public guest order tracking snapshot (scope §15, Phase 2). Never includes the
 * full address or email — access is gated by code + phone last-4.
 */
public record PublicOrderTrackingResponse(
        String orderId,
        String orderCode,
        String status,
        String fulfillmentStatus,
        BigDecimal grandTotal,
        String currency,
        String catalogBranchName,
        Instant createdAt
) {
}
