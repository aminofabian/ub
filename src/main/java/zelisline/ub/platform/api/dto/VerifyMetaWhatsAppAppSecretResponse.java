package zelisline.ub.platform.api.dto;

import java.util.List;

/**
 * Result of verifying a Meta app secret against the platform access token, plus the app(s)
 * actually subscribed to the WhatsApp Business Account.
 *
 * @param verified                    true when Meta accepted the {@code appsecret_proof}
 * @param status                      {@code VALID} | {@code INVALID} | {@code UNCONFIGURED} | {@code ERROR}
 * @param usedCandidate               true when the submitted candidate secret was checked
 * @param configuredSecretPresent     true when a stored app secret exists
 * @param configuredSecretFingerprint last 4 of the stored secret ({@code …6326}) for comparison
 * @param detail                      human-readable explanation, including Meta's own message
 * @param wabaId                      WhatsApp Business Account id owning the configured number
 * @param wabaName                    WhatsApp Business Account name
 * @param subscribedApps              apps subscribed to the WABA — the one(s) whose secret signs webhooks
 */
public record VerifyMetaWhatsAppAppSecretResponse(
        boolean verified,
        String status,
        boolean usedCandidate,
        boolean configuredSecretPresent,
        String configuredSecretFingerprint,
        String detail,
        String wabaId,
        String wabaName,
        List<SubscribedApp> subscribedApps
) {
    public record SubscribedApp(String id, String name) {
    }
}
