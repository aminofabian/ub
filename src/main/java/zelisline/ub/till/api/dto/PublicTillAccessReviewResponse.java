package zelisline.ub.till.api.dto;

import java.time.Instant;

public record PublicTillAccessReviewResponse(
        String requestId,
        String status,
        String shopName,
        String branchName,
        String cashierName,
        String cashierEmail,
        String suggestedLabel,
        String deviceShortId,
        String userAgent,
        Instant lastSeenAt,
        Instant createdAt,
        boolean canApprove
) {
}
