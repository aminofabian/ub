package zelisline.ub.platform.api.dto;

/** API keys are never returned; use {@code has*} flags. */
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
        String smsProvider,
        String sozuriProject,
        String sozuriFrom,
        String sozuriType,
        String sozuriApiUrl,
        boolean hasSozuriApiKey,
        String textsmsPartnerId,
        String textsmsShortcode,
        String textsmsApiUrl,
        boolean hasTextsmsApiKey,
        boolean envDeepseekConfigured,
        boolean envRapidapiWhatsappConfigured,
        boolean envSozuriConfigured,
        boolean envTextsmsConfigured,
        boolean secretsReadable,
        String secretsError,
        boolean encryptionEphemeral
) {}
