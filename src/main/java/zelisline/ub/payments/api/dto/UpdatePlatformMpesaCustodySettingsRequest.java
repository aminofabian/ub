package zelisline.ub.payments.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdatePlatformMpesaCustodySettingsRequest(
        @NotBlank String custodyProvider
) {
}
