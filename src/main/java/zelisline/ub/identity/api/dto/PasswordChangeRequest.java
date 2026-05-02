package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
        @NotBlank @Size(max = 2048) String currentPassword,
        @NotBlank @Size(min = 8, max = 191) String newPassword
) {
}
