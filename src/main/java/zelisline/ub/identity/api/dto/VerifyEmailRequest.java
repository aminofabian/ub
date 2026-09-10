package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/auth/verify-email}.
 *
 * <p>{@code token} is either the long URL token (16+ chars) or the 6-digit
 * inbox code. When a 6-digit code is used, {@code email} is required so the
 * code can be scoped to that signup.
 */
public record VerifyEmailRequest(
        @NotBlank @Size(min = 6, max = 512) String token,
        @Email @Size(max = 255) String email
) {
    public VerifyEmailRequest(String token) {
        this(token, null);
    }
}
