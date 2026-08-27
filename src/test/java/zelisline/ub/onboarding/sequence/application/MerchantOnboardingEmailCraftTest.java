package zelisline.ub.onboarding.sequence.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
                .contains("m3-supplier.png")
                .contains("See how")
                .contains("complete-supplier-flow")
                .contains("Post a supply");
    }

    @Test
    void guideHtmlIncludesSectionsAndShotCaptions() {
        var lesson = new MerchantOnboardingEmailCraft.GuideLesson(
                "Hi Jane,",
                "Empty shelf problem.",
                "Global catalog fills it.",
                "Faster stocking.",
                List.of("Open catalog", "Import pack"),
                MerchantOnboardingEmailCraft.shotsFor(MerchantOnboardingStep.M1_FILL_SHELF),
                "Dukani imports soda pack.",
                "Search before typing.",
                "Fill a starter set first.",
                "Open the catalog.");
        String html = MerchantOnboardingEmailCraft.renderGuideHtml(
                lesson,
                "https://kiosk.ke",
                MerchantOnboardingEmailCraft.guidesFor(MerchantOnboardingStep.M1_FILL_SHELF),
                null,
                null);
        assertThat(html)
                .contains("The problem")
                .contains("How it works")
                .contains("On your screen")
                .contains("Pro tip")
                .contains("m1-fill-shelf.png")
                .contains("Products → Global catalog");
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
