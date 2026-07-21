package zelisline.ub.platform.application;

/**
 * Resolved RapidAPI WhatsApp number-lookup settings (platform DB → env defaults).
 */
public record ResolvedRapidApiWhatsappConfig(
        String apiKey,
        String host,
        String lookupUrl,
        String phoneField,
        boolean phoneDigitsOnly
) {}
