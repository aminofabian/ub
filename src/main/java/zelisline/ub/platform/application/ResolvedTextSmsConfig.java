package zelisline.ub.platform.application;

/**
 * Resolved TextSMS.co.ke settings (platform DB → optional env defaults).
 */
public record ResolvedTextSmsConfig(
        String partnerId,
        String apiKey,
        String shortcode,
        String apiUrl
) {
    public boolean ready() {
        return partnerId != null && !partnerId.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && shortcode != null && !shortcode.isBlank();
    }
}
