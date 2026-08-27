package zelisline.ub.onboarding.sequence.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import zelisline.ub.onboarding.sequence.MerchantOnboardingStep;
import zelisline.ub.platform.email.application.PlatformCampaignEmailRenderer;

class MerchantOnboardingMessageRendererTest {

    private MerchantOnboardingMessageRenderer renderer() {
        return new MerchantOnboardingMessageRenderer(
                new PlatformCampaignEmailRenderer(), new MockEnvironment());
    }

    @Test
    void renderM1IncludesCatalogCtaShotAndSeeHow() {
        var msg = renderer().render(
                MerchantOnboardingStep.M1_FILL_SHELF,
                "Jane",
                "Njeri Fresh Mart",
                "https://njerifresh.kiosk.ke",
                null);
        assertThat(msg.subject()).contains("fill your shelf");
        assertThat(msg.plainBody()).contains("Global catalog");
        assertThat(msg.plainBody()).contains("See how");
        assertThat(msg.plainBody()).contains("/help/merchants/inventory/how-to-add-products");
        assertThat(msg.ctaPath()).isEqualTo("/products/catalog");
        assertThat(msg.htmlBody()).contains("<!DOCTYPE html>");
        assertThat(msg.innerBodyHtml())
                .contains("add-product-drawer.svg")
                .contains("See how")
                .contains("how-to-add-products");
        assertThat(msg.inAppTitle()).contains("shelf");
    }

    @Test
    void renderWeekCheckinUsesCounts() {
        var snap = new MerchantOnboardingGateService.Snapshot(
                12, 10, 2, 5, true, true, false, true, "completed", null, false, false, false,
                java.time.ZoneId.of("Africa/Nairobi"));
        var msg = renderer().render(
                MerchantOnboardingStep.W_WEEK_CHECKIN,
                "Jane",
                "Njeri Fresh Mart",
                "https://njerifresh.kiosk.ke",
                snap);
        assertThat(msg.subject()).contains("12 products").contains("5 sales");
        assertThat(msg.plainBody()).contains("2 suppliers");
    }

    @Test
    void m4FallbackWithEmptyShelfReplaysCatalogPath() {
        var snap = new MerchantOnboardingGateService.Snapshot(
                0, 0, 0, 0, false, false, false, true, "completed", null, false, false, false,
                java.time.ZoneId.of("Africa/Nairobi"));
        var msg = renderer().render(
                MerchantOnboardingStep.M4_FALLBACK,
                "Jane",
                "Njeri Fresh Mart",
                "https://njerifresh.kiosk.ke",
                snap);
        assertThat(msg.ctaPath()).isEqualTo("/products/catalog");
        assertThat(msg.plainBody()).contains("Open the Global catalog");
        assertThat(msg.whatsAppBody()).contains("Global catalog");
    }

    @Test
    void m4FallbackWithStockedShelfPushesFirstSale() {
        var snap = new MerchantOnboardingGateService.Snapshot(
                30, 30, 0, 0, false, false, false, true, "completed", null, false, false, false,
                java.time.ZoneId.of("Africa/Nairobi"));
        var msg = renderer().render(
                MerchantOnboardingStep.M4_FALLBACK,
                "Jane",
                "Njeri Fresh Mart",
                "https://njerifresh.kiosk.ke",
                snap);
        assertThat(msg.ctaPath()).isEqualTo("/cashier");
        assertThat(msg.plainBody()).doesNotContain("Global catalog");
        assertThat(msg.plainBody()).contains("Open a shift");
        assertThat(msg.whatsAppBody()).contains("till is still quiet");
    }

    @Test
    void renderHtmlCarriesPreviewTextAsPreheader() {
        var msg = renderer().render(
                MerchantOnboardingStep.M1_FILL_SHELF,
                "Jane",
                "Njeri Fresh Mart",
                "https://njerifresh.kiosk.ke",
                null);
        assertThat(msg.htmlBody())
                .contains("Thousands of Kenyan-ready items")
                .contains("display:none;max-height:0;overflow:hidden;");
    }

    @Test
    void shotOverrideReplacesDefaultImage() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.onboarding.sequence.shot.m1", "https://cdn.example/m1.png");
        var renderer = new MerchantOnboardingMessageRenderer(new PlatformCampaignEmailRenderer(), env);
        var msg = renderer.render(
                MerchantOnboardingStep.M1_FILL_SHELF,
                "Jane",
                "Njeri Fresh Mart",
                "https://njerifresh.kiosk.ke",
                null);
        assertThat(msg.innerBodyHtml())
                .contains("https://cdn.example/m1.png")
                .doesNotContain("add-product-drawer.svg");
    }
}
