package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank @Size(max = 2048) String token,
        @NotBlank @Size(min = 8, max = 191) String newPassword
) {
}
