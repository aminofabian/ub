package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupplierNumberFormatTest {

    @Test
    void formatPadsToFourDigits() {
        assertThat(SupplierNumberFormat.format(1)).isEqualTo("S-0001");
        assertThat(SupplierNumberFormat.format(42)).isEqualTo("S-0042");
        assertThat(SupplierNumberFormat.format(9999)).isEqualTo("S-9999");
        assertThat(SupplierNumberFormat.format(10000)).isEqualTo("S-10000");
    }

    @Test
    void normalizeAcceptsVariants() {
        assertThat(SupplierNumberFormat.normalize("S-0001")).isEqualTo("S-0001");
        assertThat(SupplierNumberFormat.normalize("s-1")).isEqualTo("S-0001");
        assertThat(SupplierNumberFormat.normalize("1")).isEqualTo("S-0001");
        assertThat(SupplierNumberFormat.normalize("42")).isEqualTo("S-0042");
        assertThat(SupplierNumberFormat.normalize(" S-42 ")).isEqualTo("S-0042");
        assertThat(SupplierNumberFormat.normalize("")).isNull();
        assertThat(SupplierNumberFormat.normalize("abc")).isNull();
    }

    @Test
    void looksLikeSupplierNumber() {
        assertThat(SupplierNumberFormat.looksLikeSupplierNumber("S-12")).isTrue();
        assertThat(SupplierNumberFormat.looksLikeSupplierNumber("0042")).isTrue();
        assertThat(SupplierNumberFormat.looksLikeSupplierNumber("simon")).isFalse();
        assertThat(SupplierNumberFormat.looksLikeSupplierNumber("0712345678")).isFalse();
    }
}
