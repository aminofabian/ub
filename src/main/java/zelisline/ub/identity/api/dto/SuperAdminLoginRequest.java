package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuperAdminLoginRequest(
        @NotBlank @Email @Size(max = 191) String email,
        @NotBlank @Size(max = 2048) String password
) {
}
