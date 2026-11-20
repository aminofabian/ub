package zelisline.ub.pharmacy.api.dto;

import java.time.Instant;

public record DispensingApprovalResponse(
        String approvalId,
        String approverUserId,
        String approverName,
        Instant expiresAt
) {
}
