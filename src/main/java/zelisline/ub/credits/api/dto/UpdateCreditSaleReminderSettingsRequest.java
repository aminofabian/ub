package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param rapidApiKey {@code null} = leave stored key unchanged; blank = clear.
 * @param whatsappMetaAccessToken {@code null} = unchanged; blank = clear.
 * @param smsAfricasTalkingApiKey {@code null} = unchanged; blank = clear.
 * @param rapidApiHost blank = clear tenant override (use platform/env).
 * @param rapidApiLookupUrl blank = clear tenant override.
 * @param rapidApiPhoneField blank = clear tenant override.
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
        String smsAfricasTalkingApiKey
) {
}
