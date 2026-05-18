package zelisline.ub.tenancy.api.dto;

import java.time.Instant;

/**
 * User view for the super-admin business detail page — includes role
 * information so the super-admin can see who is admin, staff, etc.
 */
public record SaBusinessUserResponse(
        String id,
        String email,
        String name,
        String phone,
        String status,
        String roleKey,
        String roleName,
        String branchName,
        Instant lastLoginAt,
        Instant createdAt
) {
}
