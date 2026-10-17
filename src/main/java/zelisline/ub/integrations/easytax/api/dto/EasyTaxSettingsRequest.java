package zelisline.ub.integrations.easytax.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Update payload for the tenant's EasyTax settings. Every field is optional. The base
 * URL is not here: it is platform-owned and resolved from the selected environment.
 * A blank/absent {@code password} keeps the stored one.
 */
public record EasyTaxSettingsRequest(
        Boolean enabled,
        @Size(max = 16) String environment,
        @Size(max = 191) String username,
        @Size(max = 191) String password,
        @Size(max = 32) String tin
) {
}
