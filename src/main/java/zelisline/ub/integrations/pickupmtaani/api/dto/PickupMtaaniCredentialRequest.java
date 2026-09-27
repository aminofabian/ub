package zelisline.ub.integrations.pickupmtaani.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Super-admin write of the tenant's Pickup Mtaani API key (scope §6).
 */
public record PickupMtaaniCredentialRequest(
        @NotBlank @Size(max = 512)
        String apiKey
) {
}
