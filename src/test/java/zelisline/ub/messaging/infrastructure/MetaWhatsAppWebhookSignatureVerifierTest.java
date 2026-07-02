package zelisline.ub.messaging.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetaWhatsAppWebhookSignatureVerifierTest {

    @Test
    void skipsVerificationWhenAppSecretUnset() {
        assertThat(MetaWhatsAppWebhookSignatureVerifier.verify("", "{}", null)).isTrue();
        assertThat(MetaWhatsAppWebhookSignatureVerifier.verify(null, "{}", "sha256=deadbeef")).isTrue();
    }

    @Test
    void rejectsMissingOrMalformedHeaderWhenSecretSet() {
        assertThat(MetaWhatsAppWebhookSignatureVerifier.verify("secret", "{}", null)).isFalse();
        assertThat(MetaWhatsAppWebhookSignatureVerifier.verify("secret", "{}", "sha256=")).isFalse();
        assertThat(MetaWhatsAppWebhookSignatureVerifier.verify("secret", "{}", "md5=abc")).isFalse();
    }

    @Test
    void acceptsValidSignature() {
        String secret = "test-app-secret";
        String body = "{\"object\":\"whatsapp_business_account\"}";
        String signature = "sha256=" + hmac(secret, body);
        assertThat(MetaWhatsAppWebhookSignatureVerifier.verify(secret, body, signature)).isTrue();
    }

    @Test
    void rejectsTamperedBody() {
        String secret = "test-app-secret";
        String body = "{\"object\":\"whatsapp_business_account\"}";
        String signature = "sha256=" + hmac(secret, body);
        assertThat(MetaWhatsAppWebhookSignatureVerifier.verify(secret, body + " ", signature)).isFalse();
    }

    private static String hmac(String secret, String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
