package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import zelisline.ub.pricing.domain.TaxRate;

class ItemTaxResolverTest {

    @Test
    void explicitCodeWins() {
        TaxRate rate = taxRate(new BigDecimal("0"), "A");
        assertThat(ItemTaxResolver.resolveEtimsCode(rate)).isEqualTo("A");
    }

    @Test
    void derivesTheTwoUnambiguousRates() {
        assertThat(ItemTaxResolver.resolveEtimsCode(taxRate(new BigDecimal("16"), null))).isEqualTo("B");
        assertThat(ItemTaxResolver.resolveEtimsCode(taxRate(new BigDecimal("8"), null))).isEqualTo("E");
    }

    @Test
    void leavesZeroPercentUnsetBecauseItIsAmbiguous() {
        // 0% could be zero-rated (C) or exempt (A) — do not guess.
        assertThat(ItemTaxResolver.resolveEtimsCode(taxRate(new BigDecimal("0"), null))).isNull();
        assertThat(ItemTaxResolver.resolveEtimsCode(taxRate(new BigDecimal("5"), null))).isNull();
        assertThat(ItemTaxResolver.resolveEtimsCode(taxRate(null, null))).isNull();
    }

    private static TaxRate taxRate(BigDecimal ratePercent, String etimsTaxCode) {
        TaxRate rate = new TaxRate();
        rate.setRatePercent(ratePercent);
        rate.setEtimsTaxCode(etimsTaxCode);
        return rate;
    }
}
