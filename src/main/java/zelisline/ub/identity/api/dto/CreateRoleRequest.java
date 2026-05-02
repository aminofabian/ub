package zelisline.ub.identity.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Tenant-scoped role creation (PHASE_1_PLAN.md §2.3 — system roles are seed-only). */
public record CreateRoleRequest(
        @NotBlank @Size(max = 191)
        @Pattern(regexp = "[a-z0-9_.-]+", message = "Role key must be lowercase alphanumeric or _.-")
        String roleKey,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String description,
        List<@Size(max = 36) String> permissionIds
) {
}
