package zelisline.ub.identity.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * View of a user. {@code permissions} is computed at read time from the role's
 * grants — never persisted on the user row (PHASE_1_PLAN.md §2.3).
 */
public record UserResponse(
        String id,
        String businessId,
        String branchId,
        String email,
        String name,
        String phone,
        String status,
        RoleSummary role,
        List<String> permissions,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt
) {

    public record RoleSummary(
            String id,
            String key,
            String name,
            boolean system
    ) {
    }
}
