package zelisline.ub.platform.api.dto;

/** API keys are never returned; use {@code hasDeepseekApiKey} / {@code hasRapidapiWhatsappKey}. */
public record PlatformIntegrationsResponse(
        boolean hasDeepseekApiKey,
        String deepseekHost,
        String deepseekUrl,
        String deepseekModel,
        boolean hasRapidapiWhatsappKey,
        String rapidApiWhatsappHost,
        String rapidApiWhatsappLookupUrl,
        String rapidApiWhatsappPhoneField,
        boolean rapidApiWhatsappPhoneDigitsOnly,
        boolean envDeepseekConfigured,
        boolean envRapidapiWhatsappConfigured,
        boolean secretsReadable,
        String secretsError,
        boolean encryptionEphemeral
) {}
