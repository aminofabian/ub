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
    void htmlMatchesOrderEmailVisualSystem() {
        var renderer = new EmailVerificationEmailRenderer();
        var branding = EmailVerificationBrandingContext.fromHost(
                java.util.Optional.empty(),
                "uzapoint.kiosk.ke");
        String link = "https://uzapoint.kiosk.ke/verify-email?token=abc";
        String html = renderer.renderHtml(branding, "Jane Owner", "owner@example.com", link);

        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("Email verification");
        assertThat(html).contains("Confirm it");
        assertThat(html).contains("Confirm your email");
        assertThat(html).contains("Hi Jane");
        assertThat(html).contains(EmailVerificationEmailRenderer.PAGE_BG);
        assertThat(html).contains(EmailVerificationEmailRenderer.GREEN);
        assertThat(html).contains("href=\"" + link + "\"");
        assertThat(html).contains("If you did not create a");
        assertThat(html).contains("fonts.googleapis.com");
        assertThat(html).contains("Cormorant+Garamond");
        assertThat(html).contains("DM+Sans");
        assertThat(html).contains(EmailVerificationEmailRenderer.FONT_SERIF);
        assertThat(html).contains(EmailVerificationEmailRenderer.FONT_SANS);
        assertThat(html).contains("Uzapoint");
        assertThat(html).doesNotContain("<svg");
    }

    @Test
    void platformDefaultLeadsWithUb() {
        var renderer = new EmailVerificationEmailRenderer();
        var branding = EmailVerificationBrandingContext.platformDefault();
        assertThat(renderer.renderSubject(branding)).isEqualTo("Verify your UB account");
        String html = renderer.renderHtml(branding, "owner@example.com", "https://example.com/v");
        assertThat(html).contains(">UB<");
        assertThat(html).contains("Point of sale, inventory, and online storefront.");
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
