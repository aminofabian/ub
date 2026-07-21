package zelisline.ub.credits.api.dto;

public record CreditSaleReminderSettingsResponse(
        boolean enabled,
        String paymentAccountUrl,
        String suggestedPaymentAccountUrl,
        String whatsappMetaPhoneNumberId,
        String whatsappMetaGraphVersion,
        String smsProvider,
        String smsAfricasTalkingUsername,
        boolean hasRapidApiKey,
        String rapidApiHost,
        String rapidApiLookupUrl,
        String rapidApiPhoneField,
        boolean rapidApiPhoneDigitsOnly,
        boolean hasWhatsappMetaAccessToken,
        boolean hasSmsAfricasTalkingApiKey,
        boolean secretsReadable,
        String secretsReadError
) {
}
