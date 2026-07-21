package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param rapidApiKey {@code null} = leave stored key unchanged; blank = clear.
 * @param whatsappMetaAccessToken {@code null} = unchanged; blank = clear.
 * @param smsAfricasTalkingApiKey {@code null} = unchanged; blank = clear.
 * @param smsSozuriApiKey {@code null} = unchanged; blank = clear.
 * @param smsTextsmsApiKey {@code null} = unchanged; blank = clear.
 */
public record UpdateCreditSaleReminderSettingsRequest(
        @NotNull Boolean enabled,
        @NotBlank String paymentAccountUrl,
        String rapidApiKey,
        String rapidApiHost,
        String rapidApiLookupUrl,
        String rapidApiPhoneField,
        Boolean rapidApiPhoneDigitsOnly,
        String whatsappMetaPhoneNumberId,
        String whatsappMetaAccessToken,
        String whatsappMetaGraphVersion,
        String smsProvider,
        String smsAfricasTalkingUsername,
        String smsAfricasTalkingApiKey,
        String smsSozuriProject,
        String smsSozuriApiKey,
        String smsSozuriFrom,
        String smsSozuriType,
        String smsSozuriApiUrl,
        String smsTextsmsPartnerId,
        String smsTextsmsApiKey,
        String smsTextsmsShortcode,
        String smsTextsmsApiUrl
) {
}
