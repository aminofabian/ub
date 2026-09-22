package zelisline.ub.till.api.dto;

import java.time.Instant;

public record TillAccessRequestResponse(
        String id,
        String branchId,
        String branchName,
        String deviceKey,
        String deviceShortId,
        String requestedByName,
        String requestedByEmail,
        String suggestedLabel,
        String userAgent,
        String status,
        Instant lastSeenAt,
        Instant createdAt,
        boolean canApprove
) {
}
