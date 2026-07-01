package zelisline.ub.tenancy.api.dto;

/**
 * Per-role mobile app metadata stored on a business (shopper, cashier, etc.).
 */
public record MobileAppSettingsDto(
        String name,
        String bundleId,
        boolean whiteLabel
) {}
