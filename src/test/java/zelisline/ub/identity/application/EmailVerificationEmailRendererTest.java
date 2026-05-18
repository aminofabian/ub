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
    void htmlIncludesBrandedButtonAndVerifyLink() {
        var renderer = new EmailVerificationEmailRenderer();
        var branding = EmailVerificationBrandingContext.fromHost(
                java.util.Optional.empty(),
                "uzapoint.kiosk.ke");
        String link = "https://uzapoint.kiosk.ke/verify-email?token=abc";
        String html = renderer.renderHtml(branding, "owner@example.com", link);

        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("Verify email address");
        assertThat(html).contains("href=\"" + link + "\"");
        assertThat(html).contains("Uzapoint");
        assertThat(html).contains("Your point of sale");
        assertThat(html).contains("#0D9488");
        assertThat(html).contains("#EA580C");
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
