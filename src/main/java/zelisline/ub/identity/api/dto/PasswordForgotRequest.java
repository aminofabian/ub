package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Optional email — when absent, handler still returns {@code 204} (§3.3).
 */
public record PasswordForgotRequest(
        @Email @Size(max = 191) String email
) {
}
