package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin override: set a user's password without knowing the current one. */
public record AdminSetPasswordRequest(
        @NotBlank @Size(min = 8, max = 191) String newPassword
) {
}
