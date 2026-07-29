package zelisline.ub.payments.api.dto;

/**
 * Gateway credential fields for the admin edit form.
 * Secrets are returned in plaintext so operators can verify the stored values
 * (endpoint is permission-gated to payment settings admins).
 */
public record GatewayCredentialSettingsResponse(
        String environment,
        String tillNumber,
        /** Comma-separated extra tills subscribed for buygoods webhooks (in addition to tillNumber). */
        String webhookTillNumbers,
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
        String clientId,
        String clientSecret,
        String apiKey,
        String secretKey,
        String publicKey,
        String consumerKey,
        String consumerSecret,
        String passkey,
        boolean credentialsReadable,
        String readError
) {
    public static GatewayCredentialSettingsResponse unreadable(String readError) {
        return new GatewayCredentialSettingsResponse(
                "sandbox",
                null,
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
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                readError
        );
    }
}
