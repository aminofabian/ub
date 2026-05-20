package zelisline.ub.payments.api.dto;

/**
 * Non-secret gateway credential fields for the admin edit form.
 * Secrets are never returned; flags indicate whether each secret is already stored.
 */
public record GatewayCredentialSettingsResponse(
        String environment,
        String tillNumber,
        String shortcode,
        String shortcodeType,
        boolean hasClientId,
        boolean hasClientSecret,
        boolean hasApiKey,
        boolean hasSecretKey,
        boolean hasPublicKey,
        boolean hasConsumerKey,
        boolean hasConsumerSecret,
        boolean hasPasskey,
        boolean credentialsReadable,
        String readError
) {
    public static GatewayCredentialSettingsResponse unreadable(String readError) {
        return new GatewayCredentialSettingsResponse(
                "sandbox",
                null,
                null,
                null,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                readError
        );
    }
}
