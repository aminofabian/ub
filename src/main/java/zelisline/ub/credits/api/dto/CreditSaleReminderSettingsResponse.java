package zelisline.ub.credits.api.dto;

public record CreditSaleReminderSettingsResponse(
        boolean enabled,
        String paymentAccountUrl,
        String suggestedPaymentAccountUrl,
        String whatsappMetaPhoneNumberId,
        String whatsappMetaGraphVersion,
        String smsProvider,
        String smsAfricasTalkingUsername,
        String smsSozuriProject,
        String smsSozuriFrom,
        String smsSozuriType,
        String smsSozuriApiUrl,
        boolean hasRapidApiKey,
        String rapidApiHost,
        String rapidApiLookupUrl,
        String rapidApiPhoneField,
        boolean rapidApiPhoneDigitsOnly,
        boolean hasWhatsappMetaAccessToken,
        boolean hasSmsAfricasTalkingApiKey,
        boolean hasSmsSozuriApiKey,
        boolean secretsReadable,
        String secretsReadError
) {
}
