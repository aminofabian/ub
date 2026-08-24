package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Super-admin profile edits. Fields left {@code null} are kept unchanged; blank
 * {@code phone} clears the ops SMS alert number.
 */
public record UpdateSuperAdminProfileRequest(
        @Size(max = 255) String name,
        @Size(max = 32) String phone
) {
}
