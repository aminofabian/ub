package zelisline.ub.platform.email.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformCampaignEmailRendererTest {

    private final PlatformCampaignEmailRenderer renderer = new PlatformCampaignEmailRenderer();

    @Test
    void realLogoUrlRendersImageInHeader() {
        String html = renderer.renderHtml(new PlatformCampaignEmailRenderer.Content(
                "Subject", "<p>body</p>", "Open", "https://shop.kiosk.ke/x",
                "https://shop.kiosk.ke", "preview",
                "https://cdn.kiosk.ke/brand/kiosk-logo-mark.png", null, null));
        assertThat(html)
                .contains("<img src=\"https://cdn.kiosk.ke/brand/kiosk-logo-mark.png\"")
                .contains("alt=\"Kiosk\"");
    }

    @Test
    void blankLogoFallsBackToWordmarkBadge() {
        String html = renderer.renderHtml(new PlatformCampaignEmailRenderer.Content(
                "Subject", "<p>body</p>", "Open", "https://shop.kiosk.ke/x",
                "https://shop.kiosk.ke", "preview", null, null, null));
        assertThat(html).doesNotContain("<img src=");
        assertThat(html).contains(">K</span>");
    }

    @Test
    void muteUrlRendersFooterLink() {
        String html = renderer.renderHtml(new PlatformCampaignEmailRenderer.Content(
                "Subject", "<p>body</p>", "Open", "https://shop.kiosk.ke/x",
                "https://shop.kiosk.ke", "preview", null,
                "https://api.kiosk.ke/api/v1/public/onboarding-sequence/mute?token=abc", null));
        assertThat(html)
                .contains("Mute these tips")
                .contains("https://api.kiosk.ke/api/v1/public/onboarding-sequence/mute?token=abc");
    }

    @Test
    void customEyebrowOverridesDefault() {
        String html = renderer.renderHtml(new PlatformCampaignEmailRenderer.Content(
                "Subject", "<p>body</p>", "Open", "https://shop.kiosk.ke/x",
                "https://shop.kiosk.ke", "preview", null, null, "Getting started"));
        assertThat(html).contains("Getting started").doesNotContain("Account setup");
    }

    @Test
    void defaultEyebrowWhenBlank() {
        String html = renderer.renderHtml(
                "Subject", "<p>body</p>", "Open", "https://shop.kiosk.ke/x", "https://shop.kiosk.ke");
        assertThat(html).contains("Account setup");
    }
}
