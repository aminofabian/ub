package zelisline.ub.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BrandingLogoPromptComposerTest {

    @Test
    void composeIncludesMerchantAskAndShopName() {
        String out = BrandingLogoPromptComposer.compose(
                "A green leaf in a circle",
                "Kilimani Greens",
                "Fresh market",
                "#0D9488",
                "#5EEAD4");
        assertThat(out).contains("Kilimani Greens");
        assertThat(out).contains("Fresh market");
        assertThat(out).contains("A green leaf in a circle");
        assertThat(out).contains("#0D9488");
        assertThat(out).contains("Transparent background");
        assertThat(out).doesNotContain("—");
    }

    @Test
    void composeWorksWithShopNameOnly() {
        String out = BrandingLogoPromptComposer.compose(null, "Palmart", null, null, null);
        assertThat(out).contains("Palmart");
        assertThat(out).contains("Invent a simple, memorable mark");
    }

    @Test
    void composeRejectsEmptyInput() {
        assertThatThrownBy(() -> BrandingLogoPromptComposer.compose("  ", "", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasEnoughInput() {
        assertThat(BrandingLogoPromptComposer.hasEnoughInput("leaf", null)).isTrue();
        assertThat(BrandingLogoPromptComposer.hasEnoughInput("", "Shop")).isTrue();
        assertThat(BrandingLogoPromptComposer.hasEnoughInput(" ", " ")).isFalse();
    }

    @Test
    void ignoresInvalidHex() {
        String out = BrandingLogoPromptComposer.compose("mark", "Shop", null, "teal", "not-a-color");
        assertThat(out).doesNotContain("Brand colours");
        assertThat(out).doesNotContain("teal");
    }
}
