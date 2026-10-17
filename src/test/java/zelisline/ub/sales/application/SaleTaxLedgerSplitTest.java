package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class SaleTaxLedgerSplitTest {

    @Test
    void splitsGrossIntoNetAndContainedTax() {
        // 1,160 gross at 16% inclusive → 160 tax, 1,000 net.
        var split = SaleTaxLedgerSplit.of(new BigDecimal("1160.00"), new BigDecimal("160.00"));
        assertThat(split.net()).isEqualByComparingTo("1000.00");
        assertThat(split.tax()).isEqualByComparingTo("160.00");
        assertThat(split.net().add(split.tax())).isEqualByComparingTo("1160.00");
    }

    @Test
    void untaxedSalePostsEntirelyAsRevenue() {
        var split = SaleTaxLedgerSplit.of(new BigDecimal("500.00"), BigDecimal.ZERO);
        assertThat(split.net()).isEqualByComparingTo("500.00");
        assertThat(split.tax()).isEqualByComparingTo("0.00");
    }

    @Test
    void nullTaxIsTreatedAsZero() {
        var split = SaleTaxLedgerSplit.of(new BigDecimal("250.00"), null);
        assertThat(split.net()).isEqualByComparingTo("250.00");
        assertThat(split.tax()).isEqualByComparingTo("0.00");
    }

    @Test
    void taxIsClampedSoNetNeverGoesNegative() {
        var split = SaleTaxLedgerSplit.of(new BigDecimal("100.00"), new BigDecimal("150.00"));
        assertThat(split.net()).isEqualByComparingTo("0.00");
        assertThat(split.tax()).isEqualByComparingTo("100.00");
    }
}
