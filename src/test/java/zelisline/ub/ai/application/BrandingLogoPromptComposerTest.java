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
        assertThat(out).contains("Asset: LOGO for LIGHT chrome");
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
        assertThat(light).contains("Asset: LOGO for LIGHT chrome");
        assertThat(light).doesNotContain("DARK VARIANT");
        assertThat(dark).contains("DARK VARIANT of the attached light logo");
        assertThat(dark).contains("Recolor it only");
        assertThat(dark).doesNotContain("professional, visually appealing logo");
        assertThat(dark).doesNotContain("Asset: LOGO for LIGHT chrome");
        assertThat(dark).contains("Never a light mark trapped inside a white square");
    }

    @Test
    void darkPromptIsARecolorOfTheAttachedLightMark() {
        String dark = BrandingLogoPromptComposer.compose(
                "A green leaf in a circle",
                "Kilimani Greens",
                "Fresh market",
                "#0D9488",
                null,
                BrandingLogoPromptComposer.Theme.DARK);
        assertThat(dark).contains("Kilimani Greens");
        assertThat(dark).contains("A green leaf in a circle");
        assertThat(dark).contains("Recolor it only");
        assertThat(dark).contains("attached light logo");
        assertThat(dark).contains("source of truth");
        assertThat(dark).doesNotContain("professional, visually appealing logo");
        assertThat(dark).doesNotContain("Merchant's description");
    }

    @Test
    void composeAllowsEmptyShopNameWhenUsingDefaultBrief() {
        String out = BrandingLogoPromptComposer.compose(
                "  ", "", null, null, null, BrandingLogoPromptComposer.Theme.LIGHT);
        assertThat(out).contains("Asset: LOGO for LIGHT chrome");
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

    @Test
    void faviconAndOgPromptsStayOnTheirAsset() {
        String favicon = BrandingLogoPromptComposer.compose(
                "A green leaf in a circle",
                "Kilimani Greens",
                "Fresh market",
                "#0D9488",
                null,
                BrandingLogoPromptComposer.Theme.FAVICON);
        String og = BrandingLogoPromptComposer.compose(
                "A green leaf in a circle",
                "Kilimani Greens",
                null,
                "#0D9488",
                null,
                BrandingLogoPromptComposer.Theme.OG);
        assertThat(favicon).contains("Asset: FAVICON");
        assertThat(favicon).contains("attached logo");
        assertThat(favicon).contains("drop any wordmark");
        assertThat(favicon).doesNotContain("professional, visually appealing logo");
        assertThat(favicon).doesNotContain("Asset: LOGO for LIGHT chrome");
        assertThat(og).contains("Asset: SOCIAL SHARE IMAGE");
        assertThat(og).contains("attached logo");
        assertThat(og).contains("Kilimani Greens");
        assertThat(og).contains("source of truth");
        assertThat(og).doesNotContain("Asset: FAVICON");
    }

    @Test
    void appIconPromptRebuildsTheLogoForTheHomeScreen() {
        String out = BrandingLogoPromptComposer.compose(
                "Keep the leaf",
                "Kilimani Greens",
                "Fresh market",
                "#0D9488",
                null,
                BrandingLogoPromptComposer.Theme.APP_ICON);
        assertThat(out).contains("Asset: HOME SCREEN APP ICON");
        assertThat(out).contains("existing light-chrome logo");
        assertThat(out).contains("Opaque");
        assertThat(out).contains("Keep the leaf");
        assertThat(out).contains("source of truth");
        assertThat(out).doesNotContain("Asset: FAVICON");
        assertThat(out).doesNotContain("Transparent background");
        assertThat(out).doesNotContain("professional, visually appealing logo");
    }
}
