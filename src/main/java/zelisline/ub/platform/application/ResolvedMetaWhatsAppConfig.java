package zelisline.ub.platform.application;

/**
 * Resolved Meta WhatsApp Cloud API settings (platform DB → env defaults).
 */
public record ResolvedMetaWhatsAppConfig(
        String accessToken,
        String phoneNumberId,
        String graphVersion,
        String webhookVerifyToken,
        String appSecret
) {
    public boolean configured() {
        return accessToken != null
                && !accessToken.isBlank()
                && phoneNumberId != null
                && !phoneNumberId.isBlank();
    }
}
