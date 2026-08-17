package zelisline.ub.credits.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskedMsisdnTest {

    @Test
    void compactPreservesX() {
        assertThat(MaskedMsisdn.compact("+2547XXXXX123")).isEqualTo("2547XXXXX123");
        assertThat(MaskedMsisdn.isMasked("+2547XXXXX123")).isTrue();
    }

    @Test
    void fingerprintUsesVisiblePrefixAndSuffix() {
        assertThat(MaskedMsisdn.fingerprint("+2547XXXXX123")).isEqualTo("2547|123");
        assertThat(MaskedMsisdn.fingerprint("254712345678")).isEqualTo("2547|678");
    }

    @Test
    void assignedReplacesXWithConstantFiller() {
        assertThat(MaskedMsisdn.assignedMsisdn("+2547XXXXX123")).isEqualTo("254700000123");
        assertThat(MaskedMsisdn.displayAssigned("+2547XXXXX123")).isEqualTo("0700000123");
        assertThat(MaskedMsisdn.displayMasked("+2547XXXXX123")).isEqualTo("07•••••123");
    }

    @Test
    void completeWithDigitsFitsMask() {
        assertThat(MaskedMsisdn.completeWithDigits("+2547XXXXX123", "12345"))
                .isEqualTo("254712345123");
        assertThat(MaskedMsisdn.fitsMask("+2547XXXXX123", "254712345123")).isTrue();
        assertThat(MaskedMsisdn.fitsMask("+2547XXXXX123", "254799999999")).isFalse();
        assertThat(MaskedMsisdn.completeWithDigits("+2547XXXXX123", "12")).isNull();
    }

    @Test
    void digitStripWouldHaveDestroyedTheMask() {
        String stripped = "+2547XXXXX123".replaceAll("[^0-9]", "");
        assertThat(stripped).isEqualTo("2547123");
        assertThat(MaskedMsisdn.compact("+2547XXXXX123")).isNotEqualTo(stripped);
    }
}
