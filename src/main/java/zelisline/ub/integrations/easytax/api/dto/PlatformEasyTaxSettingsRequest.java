package zelisline.ub.integrations.easytax.api.dto;

import jakarta.validation.constraints.Size;

/** Super-admin payload for the platform-wide EasyTax configuration. */
public record PlatformEasyTaxSettingsRequest(
        Boolean enabled,
        @Size(max = 255) String sandboxBaseUrl,
        @Size(max = 255) String productionBaseUrl
) {
}
