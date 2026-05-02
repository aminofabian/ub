package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Payload for {@code PATCH /api/v1/users/{id}}. All fields are optional —
 * absent fields are left unchanged. Role assignment lives behind a separate
 * endpoint ({@code POST /users/{id}/role}) so the {@code users.update} and
 * {@code users.assign_role} permissions can be split.
 */
public record UpdateUserRequest(
        @Size(max = 255) String name,
        @Size(max = 50) String phone,
        @Size(max = 36) String branchId,
        @Size(max = 32) String status
) {
}
