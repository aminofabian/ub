package zelisline.ub.integrations.whatsapp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import zelisline.ub.platform.application.ResolvedMetaWhatsAppConfig;

/**
 * Covers the branches that decide whether the app-secret check can run at all. The Graph call
 * itself is exercised only in production/staging; these paths return before any HTTP is attempted.
 */
class MetaWhatsAppAppSecretVerifierTest {

    private final MetaWhatsAppAppSecretVerifier verifier = new MetaWhatsAppAppSecretVerifier();

    @Test
    void unconfiguredWhenAccessTokenMissing() {
        MetaWhatsAppAppSecretVerifier.Result result = verifier.verify(config(null, "123", "secret"), null);
        assertThat(result.status()).isEqualTo(MetaWhatsAppAppSecretVerifier.Status.UNCONFIGURED);
        assertThat(result.verified()).isFalse();
    }

    @Test
    void unconfiguredWhenPhoneNumberIdMissing() {
        MetaWhatsAppAppSecretVerifier.Result result = verifier.verify(config("token", null, "secret"), null);
        assertThat(result.status()).isEqualTo(MetaWhatsAppAppSecretVerifier.Status.UNCONFIGURED);
    }

    @Test
    void unconfiguredWhenNoSecretAndNoCandidate() {
        MetaWhatsAppAppSecretVerifier.Result result = verifier.verify(config("token", "123", null), null);
        assertThat(result.status()).isEqualTo(MetaWhatsAppAppSecretVerifier.Status.UNCONFIGURED);
        assertThat(result.configuredSecretPresent()).isFalse();
    }

    @Test
    void candidateIsReportedEvenWhenConfigIsIncomplete() {
        MetaWhatsAppAppSecretVerifier.Result result = verifier.verify(null, "candidate-secret");
        assertThat(result.status()).isEqualTo(MetaWhatsAppAppSecretVerifier.Status.UNCONFIGURED);
        assertThat(result.usedCandidate()).isTrue();
    }

    private static ResolvedMetaWhatsAppConfig config(String token, String phoneNumberId, String appSecret) {
        return new ResolvedMetaWhatsAppConfig(token, phoneNumberId, "v25.0", null, appSecret, "test");
    }
}
