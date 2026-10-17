package zelisline.ub.integrations.easytax.api.dto;

/** Supplier-side EasyTax state. The password is never returned — only {@code hasPassword}. */
public record SupplierEasyTaxSettingsResponse(
        boolean enabled,
        String environment,
        String username,
        boolean hasPassword,
        String tin,
        String kraBranchId,
        String deviceSerial,
        /** The supplier has supplied TIN, credentials, KRA branch id, and device serial. */
        boolean configured,
        /** The platform administrator has enabled EasyTax. */
        boolean platformEnabled,
        /** Endpoint for the selected environment; null when unavailable. */
        String resolvedBaseUrl,
        String lastTestedAt,
        Boolean lastTestOk,
        String lastTestMessage
) {
}
