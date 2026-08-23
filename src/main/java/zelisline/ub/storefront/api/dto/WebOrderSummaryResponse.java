package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record WebOrderSummaryResponse(
        String id,
        String orderCode,
        String channel,
        String status,
        String fulfillmentStatus,
        String handoffState,
        Instant handoffOpenedAt,
        Instant expiresAt,
        BigDecimal grandTotal,
        String currency,
        String customerName,
        String customerPhone,
        String catalogBranchId,
        String catalogBranchName,
        Instant createdAt
) {}
