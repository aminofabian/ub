package zelisline.ub.platform.application;

/**
 * Resolved Ping Africa Bulk SMS settings (platform DB → optional env defaults).
 * <p>The base URL carries the provider default, so only the Bearer token is
 * strictly required to send.
 */
public record ResolvedPingAfricaSmsConfig(
        String provider,
        String apiKey,
        String baseUrl,
        String senderId
) {
    public boolean pingAfricaReady() {
        return "ping_africa".equalsIgnoreCase(provider)
                && apiKey != null && !apiKey.isBlank();
    }
}
