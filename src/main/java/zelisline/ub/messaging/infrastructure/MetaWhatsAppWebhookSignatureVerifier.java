package zelisline.ub.messaging.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Validates {@code X-Hub-Signature-256} on Meta WhatsApp webhook POST bodies.
 */
public final class MetaWhatsAppWebhookSignatureVerifier {

    private static final String HEADER_PREFIX = "sha256=";

    private MetaWhatsAppWebhookSignatureVerifier() {
    }

    /**
     * @param appSecret Meta app secret; when blank, verification is skipped (dev only).
     */
    public static boolean verify(String appSecret, String rawBody, String signatureHeader) {
        if (appSecret == null || appSecret.isBlank()) {
            return true;
        }
        if (signatureHeader == null || !signatureHeader.startsWith(HEADER_PREFIX)) {
            return false;
        }
        String provided = signatureHeader.substring(HEADER_PREFIX.length()).trim();
        if (provided.isEmpty()) {
            return false;
        }
        String expected = hmacSha256Hex(appSecret, rawBody == null ? "" : rawBody);
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Multi-app verification (Model B): accept the webhook when <em>any</em> supplied secret signs
     * the body. When no secret is configured at all, verification is skipped (dev only).
     */
    public static boolean verifyAny(Collection<String> appSecrets, String rawBody, String signatureHeader) {
        if (appSecrets == null || appSecrets.isEmpty()) {
            return true;
        }
        for (String secret : appSecrets) {
            if (secret != null && !secret.isBlank() && verify(secret, rawBody, signatureHeader)) {
                return true;
            }
        }
        return false;
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 failed", ex);
        }
    }
}
