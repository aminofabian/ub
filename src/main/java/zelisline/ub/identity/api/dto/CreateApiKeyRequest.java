package zelisline.ub.identity.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(
        @NotBlank @Size(max = 255) String label,
        List<@NotBlank @Size(max = 64) String> scopes
) {
    public CreateApiKeyRequest {
        scopes = scopes == null ? List.of() : scopes;
    }
}
