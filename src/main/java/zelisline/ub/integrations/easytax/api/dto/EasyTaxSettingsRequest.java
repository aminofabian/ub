package zelisline.ub.integrations.easytax.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Update payload for the EasyTax integration. Every field is optional so the
 * operator can change one value at a time. A blank/absent {@code password}
 * keeps the stored one; anything else replaces it.
 */
public record EasyTaxSettingsRequest(
        Boolean enabled,
        @Size(max = 16) String environment,
        @Size(max = 255) String baseUrl,
        @Size(max = 191) String username,
        @Size(max = 191) String password,
        @Size(max = 32) String tin,
        @Size(max = 32) String branchId,
        @Size(max = 64) String deviceSerial
) {
}
