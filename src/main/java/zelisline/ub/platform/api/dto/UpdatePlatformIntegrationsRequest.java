package zelisline.ub.platform.api.dto;

/**
 * Secret fields: {@code null} = leave unchanged; blank string = clear stored value.
 * Non-secret endpoint fields: {@code null} = leave unchanged; blank = clear (fall back to env).
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
        Boolean rapidApiWhatsappPhoneDigitsOnly
) {}
