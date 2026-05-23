package zelisline.ub.platform.api.dto;

/**
 * Secret fields: {@code null} = leave unchanged; blank string = clear stored value.
 */
public record UpdatePlatformIntegrationsRequest(
        String deepseekApiKey,
        String deepseekHost,
        String deepseekUrl,
        String deepseekModel,
        String rapidApiWhatsappKey
) {}
