package zelisline.ub.integrations.easytax.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Supplier opt-in payload. The endpoint is platform-owned; the supplier supplies its
 * own TIN, credentials, and the KRA branch id + device serial of its VSCU.
 * A blank/absent {@code password} keeps the stored one.
 */
public record SupplierEasyTaxSettingsRequest(
        Boolean enabled,
        @Size(max = 16) String environment,
        @Size(max = 191) String username,
        @Size(max = 191) String password,
        @Size(max = 32) String tin,
        @Size(max = 32) String kraBranchId,
        @Size(max = 64) String deviceSerial
) {
}
