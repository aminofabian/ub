package zelisline.ub.platform.application;

/**
 * Resolved Meta WhatsApp Cloud API settings (platform DB → env defaults).
 */
public record ResolvedMetaWhatsAppConfig(
        String accessToken,
        String phoneNumberId,
        String graphVersion,
        String webhookVerifyToken,
        String appSecret,
        String accessTokenSource
) {
    public boolean configured() {
        return accessToken != null
                && !accessToken.isBlank()
                && phoneNumberId != null
                && !phoneNumberId.isBlank();
    }

    /** Last 4 characters of the token for support diagnostics (never the full secret). */
    public String accessTokenFingerprint() {
        if (accessToken == null || accessToken.isBlank()) {
            return "none";
        }
        String t = accessToken.trim();
        if (t.length() <= 4) {
            return "****";
        }
        return "…" + t.substring(t.length() - 4);
    }
}
