package zelisline.ub.integrations.easytax.api.dto;

/**
 * Read model for the EasyTax integration. The password is never returned —
 * only {@code hasPassword} so the UI can show whether one is stored.
 */
public record EasyTaxSettingsResponse(
        boolean enabled,
        String environment,
        String baseUrl,
        String username,
        boolean hasPassword,
        String tin,
        String branchId,
        String deviceSerial,
        /** Every required field is present, so the integration can run. */
        boolean configured,
        String lastTestedAt,
        Boolean lastTestOk,
        String lastTestMessage
) {
}
