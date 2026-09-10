package zelisline.ub.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrandingLogoPromptComposerTest {

    @Test
    void composeIncludesMerchantAskAndShopName() {
        String out = BrandingLogoPromptComposer.compose(
                "A green leaf in a circle",
                "Kilimani Greens",
                "Fresh market",
                "#0D9488",
                "#5EEAD4",
                BrandingLogoPromptComposer.Theme.LIGHT);
        assertThat(out).contains("Kilimani Greens");
        assertThat(out).contains("Fresh market");
        assertThat(out).contains("A green leaf in a circle");
        assertThat(out).contains("#0D9488");
        assertThat(out).contains("Theme: LIGHT");
        assertThat(out).contains("Transparent background");
        assertThat(out).doesNotContain("—");
    }

    @Test
    void emptyPromptUsesDefaultBriefAndBuildsDarkVariant() {
        String light = BrandingLogoPromptComposer.compose(
                null, "Palmart", null, null, null, BrandingLogoPromptComposer.Theme.LIGHT);
        String dark = BrandingLogoPromptComposer.compose(
                "  ", "Palmart", null, null, null, BrandingLogoPromptComposer.Theme.DARK);
        assertThat(light).contains("Palmart");
        assertThat(light).contains("professional, visually appealing logo");
        assertThat(light).contains("Theme: LIGHT");
        assertThat(light).doesNotContain("Theme: DARK");
        assertThat(dark).contains("Theme: DARK");
        assertThat(dark).contains("same core iconography");
        assertThat(dark).doesNotContain("Theme: LIGHT");
    }

    @Test
    void composeAllowsEmptyShopNameWhenUsingDefaultBrief() {
        String out = BrandingLogoPromptComposer.compose(
                "  ", "", null, null, null, BrandingLogoPromptComposer.Theme.LIGHT);
        assertThat(out).contains("Theme: LIGHT");
        assertThat(out).contains("professional, visually appealing logo");
    }

    @Test
    void hasEnoughInputAlwaysAllowsGenerate() {
        assertThat(BrandingLogoPromptComposer.hasEnoughInput("leaf", null)).isTrue();
        assertThat(BrandingLogoPromptComposer.hasEnoughInput("", "Shop")).isTrue();
        assertThat(BrandingLogoPromptComposer.hasEnoughInput(" ", " ")).isTrue();
    }

    @Test
    void ignoresInvalidHex() {
        String out = BrandingLogoPromptComposer.compose(
                "mark", "Shop", null, "teal", "not-a-color", BrandingLogoPromptComposer.Theme.LIGHT);
        assertThat(out).doesNotContain("Brand colours");
        assertThat(out).doesNotContain("teal");
    }
}
