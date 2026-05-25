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
        /**
         * IDs of item types (departments) this user is restricted to. Only
         * meaningful for roles whose catalog access is scoped by department
         * (e.g. {@code grocery_clerk}); other roles see the empty list and
         * are unrestricted.
         */
        List<String> itemTypeIds,
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
