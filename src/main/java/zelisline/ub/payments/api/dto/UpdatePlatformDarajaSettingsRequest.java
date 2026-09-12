package zelisline.ub.payments.api.dto;

/**
 * Super-admin update body for platform Daraja. Null fields are left unchanged.
 * Secret fields are write-only; omit to keep existing encrypted values.
 */
public record UpdatePlatformDarajaSettingsRequest(
        Boolean enabled,
        String environment,
        String shortcodeType,
        String shortcode,
        String consumerKey,
        String consumerSecret,
        String passkey,
        Boolean clearCredentials
) {
}
