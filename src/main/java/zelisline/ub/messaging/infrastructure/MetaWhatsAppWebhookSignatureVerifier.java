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
        return verify(appSecret,
                rawBody == null ? null : rawBody.getBytes(StandardCharsets.UTF_8),
                signatureHeader);
    }

    /**
     * Verifies {@code X-Hub-Signature-256} against the exact bytes Meta signed. Prefer this over
     * the {@code String} overload: the HMAC is computed over the wire bytes, so no re-serialisation
     * of the body (line folding, trimming, charset guessing) can invalidate an otherwise valid
     * signature.
     *
     * @param appSecret Meta app secret; when blank, verification is skipped (dev only).
     */
    public static boolean verify(String appSecret, byte[] rawBody, String signatureHeader) {
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
        String expected = hmacSha256Hex(appSecret, rawBody == null ? new byte[0] : rawBody);
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Multi-app verification (Model B): accept the webhook when <em>any</em> supplied secret signs
     * the body. When no secret is configured at all, verification is skipped (dev only).
     */
    public static boolean verifyAny(Collection<String> appSecrets, String rawBody, String signatureHeader) {
        return verifyAny(appSecrets,
                rawBody == null ? null : rawBody.getBytes(StandardCharsets.UTF_8),
                signatureHeader);
    }

    /** Raw-byte variant of {@link #verifyAny(Collection, String, String)}. */
    public static boolean verifyAny(Collection<String> appSecrets, byte[] rawBody, String signatureHeader) {
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

    private static String hmacSha256Hex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload);
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
