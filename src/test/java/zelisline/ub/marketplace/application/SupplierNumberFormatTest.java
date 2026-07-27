package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupplierNumberFormatTest {

    @Test
    void formatPadsToSixDigits() {
        assertThat(SupplierNumberFormat.format(1)).isEqualTo("S-000001");
        assertThat(SupplierNumberFormat.format(42)).isEqualTo("S-000042");
        assertThat(SupplierNumberFormat.format(1000001)).isEqualTo("S-1000001");
    }

    @Test
    void normalizeAcceptsVariants() {
        assertThat(SupplierNumberFormat.normalize("S-000001")).isEqualTo("S-000001");
        assertThat(SupplierNumberFormat.normalize("s-1")).isEqualTo("S-000001");
        assertThat(SupplierNumberFormat.normalize("1")).isEqualTo("S-000001");
        assertThat(SupplierNumberFormat.normalize(" S-42 ")).isEqualTo("S-000042");
        assertThat(SupplierNumberFormat.normalize("")).isNull();
        assertThat(SupplierNumberFormat.normalize("abc")).isNull();
    }
}
