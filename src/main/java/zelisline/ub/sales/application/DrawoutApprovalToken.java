package zelisline.ub.sales.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signed tokens for drawout approve/reject links (WhatsApp / SMS, no login).
 * Format: {@code base64url(drawoutId).base64url(expEpochSec).base64url(hmac)}.
 */
@Component
public class DrawoutApprovalToken {

    private final byte[] secret;

    public DrawoutApprovalToken(@Value("${app.jwt.secret:}") String jwtSecret) {
        String seed = jwtSecret == null || jwtSecret.isBlank()
                ? "dev-drawout-approval-secret"
                : jwtSecret;
        this.secret = seed.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String drawoutId, Instant expiresAt) {
        String exp = Long.toString(expiresAt.getEpochSecond());
        String payload = encode(drawoutId) + "." + encode(exp);
        return payload + "." + encode(hmac(payload));
    }

    public String verifyDrawoutId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String payload = parts[0] + "." + parts[1];
        String expected = encode(hmac(payload));
        if (!constantTimeEquals(expected, parts[2])) {
            return null;
        }
        String drawoutId = decode(parts[0]);
        long exp;
        try {
            exp = Long.parseLong(decode(parts[1]));
        } catch (RuntimeException ex) {
            return null;
        }
        if (Instant.now().getEpochSecond() > exp) {
            return null;
        }
        return drawoutId == null || drawoutId.isBlank() ? null : drawoutId;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String decode(String raw) {
        try {
            return new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
