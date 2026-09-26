package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleOAuthStartRequest(
        @NotBlank String intent,
        String next,
        String businessId
) {}
