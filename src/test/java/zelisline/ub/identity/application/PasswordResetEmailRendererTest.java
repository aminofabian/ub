package zelisline.ub.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PasswordResetEmailRendererTest {

    @Test
    void bodyMatchesGoldenFile() throws Exception {
        var renderer = new PasswordResetEmailRenderer();
        String actual = renderer.renderBody(
                "owner@example.com",
                "http://localhost:5173/reset-password?token=abc");
        String expected = new String(
                getClass().getResourceAsStream("/golden/password-reset-email.txt").readAllBytes(),
                StandardCharsets.UTF_8
        ).trim();
        assertThat(actual.trim()).isEqualTo(expected);
    }
}
