package zelisline.ub.identity.api.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * Patch payload for tenant-scoped roles. {@code permissionIds} is interpreted
 * as the full desired set when present (replace-all); {@code null} leaves the
 * grants untouched.
 *
 * <p>System roles ({@code is_system = true}) cannot be edited — guarded at
 * the service layer (PHASE_1_PLAN.md §2.3).
 */
public record UpdateRoleRequest(
        @Size(max = 255) String name,
        @Size(max = 500) String description,
        List<@Size(max = 36) String> permissionIds
) {
}
