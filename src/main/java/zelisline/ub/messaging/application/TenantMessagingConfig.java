package zelisline.ub.messaging.application;

/**
 * Resolved per-tenant messaging credentials for credit tab sale reminders.
 */
public record TenantMessagingConfig(
        boolean enabled,
        String paymentAccountUrl,
        String rapidApiKey,
        String rapidApiHost,
        String rapidApiLookupUrl,
        String metaAccessToken,
        String metaPhoneNumberId,
        String metaGraphVersion,
        String smsProvider,
        String smsUsername,
        String smsApiKey,
        boolean secretsReadable,
        String secretsReadError
) {
    public boolean rapidApiConfigured() {
        return rapidApiKey != null && !rapidApiKey.isBlank();
    }

    public boolean metaWhatsAppConfigured() {
        return metaAccessToken != null && !metaAccessToken.isBlank()
                && metaPhoneNumberId != null && !metaPhoneNumberId.isBlank();
    }

    public boolean smsConfigured() {
        return "africas_talking".equalsIgnoreCase(smsProvider)
                && smsUsername != null && !smsUsername.isBlank()
                && smsApiKey != null && !smsApiKey.isBlank();
    }
}
