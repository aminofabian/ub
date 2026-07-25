package zelisline.ub.messages.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicContactMessageRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @Size(max = 32) String phone,
        @NotBlank @Size(max = 4000) String message,
        @Size(max = 512) String sourcePath
) {}
