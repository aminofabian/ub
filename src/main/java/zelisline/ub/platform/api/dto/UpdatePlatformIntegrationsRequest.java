package zelisline.ub.platform.api.dto;

/**
 * Secret fields: {@code null} = leave unchanged; blank string = clear stored value.
 * Non-secret fields: {@code null} = leave unchanged; blank = clear (fall back to env if any).
 */
public record UpdatePlatformIntegrationsRequest(
        String deepseekApiKey,
        String deepseekHost,
        String deepseekUrl,
        String deepseekModel,
        String rapidApiWhatsappKey,
        String rapidApiWhatsappHost,
        String rapidApiWhatsappLookupUrl,
        String rapidApiWhatsappPhoneField,
        Boolean rapidApiWhatsappPhoneDigitsOnly,
        String smsProvider,
        String sozuriProject,
        String sozuriApiKey,
        String sozuriFrom,
        String sozuriType,
        String sozuriApiUrl,
        String textsmsPartnerId,
        String textsmsApiKey,
        String textsmsShortcode,
        String textsmsApiUrl
) {}
