package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload for {@code POST /api/v1/auth/verify-email}. */
public record VerifyEmailRequest(
        @NotBlank @Size(min = 16, max = 512) String token
) {
}
