package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PublicDrawoutReviewResponse(
        String drawoutId,
        String shiftId,
        String status,
        String category,
        BigDecimal amount,
        String currency,
        String description,
        String recipientName,
        String initiatedByName,
        String shopName,
        Instant createdAt,
        Instant expiresAt,
        boolean canApprove
) {
}
