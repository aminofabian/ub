package zelisline.ub.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class EmailVerificationEmailRendererTest {

    @Test
    void plainTextMatchesGoldenFile() throws Exception {
        var renderer = new EmailVerificationEmailRenderer();
        var branding = EmailVerificationBrandingContext.platformDefault();
        String actual = renderer.renderPlainText(
                branding,
                "owner@example.com",
                "http://localhost:5173/verify-email?token=abc");
        String expected = new String(
                getClass().getResourceAsStream("/golden/email-verification-email.txt").readAllBytes(),
                StandardCharsets.UTF_8
        ).trim();
        assertThat(actual.trim()).isEqualTo(expected);
    }

    @Test
    void htmlMatchesMockupLayoutAndGreenPalette() {
        var renderer = new EmailVerificationEmailRenderer();
        var branding = EmailVerificationBrandingContext.fromHost(
                java.util.Optional.empty(),
                "uzapoint.kiosk.ke");
        String link = "https://uzapoint.kiosk.ke/verify-email?token=abc";
        String html = renderer.renderHtml(branding, "owner@example.com", link);

        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("Verify Your Email");
        assertThat(html).contains("Confirm your email");
        assertThat(html).contains("Please click the button below to confirm your email");
        assertThat(html).contains(EmailVerificationEmailRenderer.BG_MINT);
        assertThat(html).contains(EmailVerificationEmailRenderer.HERO_BG);
        assertThat(html).contains(EmailVerificationEmailRenderer.GREEN);
        assertThat(html).contains("href=\"" + link + "\"");
        assertThat(html).contains("If you did not request this, no worries");
        assertThat(html).contains("<svg");
    }

    @Test
    void subjectUsesDisplayName() {
        var renderer = new EmailVerificationEmailRenderer();
        var branding = EmailVerificationBrandingContext.fromHost(
                java.util.Optional.empty(),
                "uzapoint.kiosk.ke");
        assertThat(renderer.renderSubject(branding)).isEqualTo("Verify your Uzapoint account");
    }
}
