package zelisline.ub.integrations.webhook.application;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * HMAC signing for webhook HTTP bodies ({@code sale.completed}, etc.).
 *
 * <p>{@code X-Kiosk-Signature} format: {@code t={unix_seconds},v1={hex_hmac}} where the signed
 * material is "{@code timestamp + "." + rawBody}}" (Stripe-style).</p>
 */
@Component
public class WebhookSigner {

    public static final String HEADER_SIGNATURE = "X-Kiosk-Signature";

    /** Hex HMAC-SHA256 of ({@code epochSeconds + "." + bodyUtf8}). */
    public String sign(long epochSeconds, String bodyUtf8, String signingSecret) {
        String message = epochSeconds + "." + bodyUtf8;
        return hmacHex(signingSecret, message);
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
