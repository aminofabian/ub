package zelisline.ub.notifications.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterFcmTokenRequest(
        @NotBlank String platform,
        @NotBlank String token
) {
}
