package zelisline.ub.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class EmailVerificationEmailRendererTest {

    @Test
    void bodyMatchesGoldenFile() throws Exception {
        var renderer = new EmailVerificationEmailRenderer();
        String actual = renderer.renderBody(
                "owner@example.com",
                "http://localhost:5173/verify-email?token=abc");
        String expected = new String(
                getClass().getResourceAsStream("/golden/email-verification-email.txt").readAllBytes(),
                StandardCharsets.UTF_8
        ).trim();
        assertThat(actual.trim()).isEqualTo(expected);
    }
}
