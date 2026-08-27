package zelisline.ub.onboarding.sequence.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class MerchantOnboardingMuteTokenTest {

    @Test
    void roundTripIssuesAndVerifies() {
        var tokens = new MerchantOnboardingMuteToken("test-secret");
        String token = tokens.issue("biz-123", Instant.now().plusSeconds(3600));
        assertThat(tokens.verifyBusinessId(token)).isEqualTo("biz-123");
    }

    @Test
    void rejectsTamperedToken() {
        var tokens = new MerchantOnboardingMuteToken("test-secret");
        String token = tokens.issue("biz-123", Instant.now().plusSeconds(3600));
        assertThat(tokens.verifyBusinessId(token + "x")).isNull();
        assertThat(tokens.verifyBusinessId(null)).isNull();
    }

    @Test
    void rejectsExpiredToken() {
        var tokens = new MerchantOnboardingMuteToken("test-secret");
        String token = tokens.issue("biz-123", Instant.now().minusSeconds(10));
        assertThat(tokens.verifyBusinessId(token)).isNull();
    }
}
