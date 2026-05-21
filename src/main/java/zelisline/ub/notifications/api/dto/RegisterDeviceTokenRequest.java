package zelisline.ub.notifications.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceTokenRequest(
        @NotBlank String endpoint,
        @NotBlank String p256dh,
        @NotBlank String auth,
        String platform
) {
}
