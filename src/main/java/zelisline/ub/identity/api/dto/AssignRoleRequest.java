package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload for {@code POST /api/v1/users/{id}/role}. */
public record AssignRoleRequest(
        @NotBlank @Size(max = 36) String roleId
) {
}
