package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload for {@code POST /api/v1/auth/register}. */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 191) String email,
        @NotBlank @Size(min = 1, max = 255) String name,
        @NotBlank @Size(min = 8, max = 191) String password
) {
}
