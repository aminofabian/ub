package zelisline.ub.onboarding.sequence.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import zelisline.ub.onboarding.sequence.MerchantOnboardingStep;

class MerchantOnboardingEmailCraftTest {

    @Test
    void m1GuidesPointAtProductHelp() {
        var guides = MerchantOnboardingEmailCraft.guidesFor(MerchantOnboardingStep.M1_FILL_SHELF);
        assertThat(guides).isNotEmpty();
        assertThat(guides.getFirst().path()).contains("/help/merchants/");
    }

    @Test
    void innerHtmlIncludesShotAndSeeHowCallout() {
        var guides = MerchantOnboardingEmailCraft.guidesFor(MerchantOnboardingStep.M3_MONEY_LOOP);
        var shot = MerchantOnboardingEmailCraft.defaultShot(MerchantOnboardingStep.M3_MONEY_LOOP);
        String html = MerchantOnboardingEmailCraft.innerHtml(
                "Hi Jane,\n\nPost a supply.",
                "https://kiosk.ke",
                guides,
                shot,
                null);
        assertThat(html)
                .contains("m3-money-loop.png")
                .contains("See how")
                .contains("complete-supplier-flow")
                .contains("Post a supply");
    }

    @Test
    void appendGuidesAddsPlainLinks() {
        String plain = MerchantOnboardingEmailCraft.appendGuidesToPlain(
                "Lesson body",
                "https://kiosk.ke/",
                MerchantOnboardingEmailCraft.guidesFor(MerchantOnboardingStep.M6_TEAM));
        assertThat(plain)
                .contains("Lesson body")
                .contains("See how")
                .contains("https://kiosk.ke/help/merchants/staff-branches/invite-your-first-staff");
    }
}
