package zelisline.ub.tenancy.api.dto;

import java.time.Instant;

public record BranchResponse(
        String id,
        String businessId,
        String name,
        String address,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
