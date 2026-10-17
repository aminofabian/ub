package zelisline.ub.integrations.easytax.api.dto;

/**
 * Read model for the tenant's EasyTax integration. The password is never returned —
 * only {@code hasPassword}. The endpoint and platform availability are read-only
 * values supplied by the platform administrator.
 */
public record EasyTaxSettingsResponse(
        boolean enabled,
        String environment,
        String username,
        boolean hasPassword,
        String tin,
        /** This tenant has supplied username, password, and TIN. */
        boolean configured,
        /** The platform administrator has enabled EasyTax. */
        boolean platformEnabled,
        /** Endpoint the tenant will use for the selected environment; null when unavailable. */
        String resolvedBaseUrl,
        String lastTestedAt,
        Boolean lastTestOk,
        String lastTestMessage
) {
}
