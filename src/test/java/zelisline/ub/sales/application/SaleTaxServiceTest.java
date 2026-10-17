package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.catalog.application.ItemTaxResolver;
import zelisline.ub.sales.domain.SaleItem;

@ExtendWith(MockitoExtension.class)
class SaleTaxServiceTest {

    @Mock
    private ItemTaxResolver itemTaxResolver;

    @Test
    void extractsTaxContainedInAGrossAmount() {
        // 16% inclusive on 116.00 → 16.00 tax, 100.00 net.
        assertThat(SaleTaxService.extractTax(new BigDecimal("116.00"), new BigDecimal("16")))
                .isEqualByComparingTo("16.00");
        // 8% inclusive on 108.00 → 8.00 tax.
        assertThat(SaleTaxService.extractTax(new BigDecimal("108.00"), new BigDecimal("8")))
                .isEqualByComparingTo("8.00");
        // 16% inclusive on 100.00 → 13.79 tax (rounded).
        assertThat(SaleTaxService.extractTax(new BigDecimal("100.00"), new BigDecimal("16")))
                .isEqualByComparingTo("13.79");
    }

    @Test
    void treatsZeroOrAbsentRateAsNoTax() {
        assertThat(SaleTaxService.extractTax(new BigDecimal("50.00"), BigDecimal.ZERO))
                .isEqualByComparingTo("0.00");
        assertThat(SaleTaxService.extractTax(new BigDecimal("50.00"), null))
                .isEqualByComparingTo("0.00");
        assertThat(SaleTaxService.extractTax(new BigDecimal("50.00"), new BigDecimal("-5")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void stampsTaxOnTaxedLinesAndSkipsUntaxedAndItemless() {
        when(itemTaxResolver.resolve("biz", "i1")).thenReturn(
                new ItemTaxResolver.ResolvedTax("t1", "VAT 16", new BigDecimal("16"), true, "B"));
        when(itemTaxResolver.resolve("biz", "i2")).thenReturn(null);

        SaleItem taxed = saleItem("i1", "116.00");
        SaleItem taxedAgain = saleItem("i1", "58.00");
        SaleItem untaxed = saleItem("i2", "40.00");
        SaleItem airtime = saleItem(null, "25.00");

        BigDecimal total = new SaleTaxService(itemTaxResolver)
                .applyTax("biz", List.of(taxed, taxedAgain, untaxed, airtime));

        assertThat(taxed.getTaxRateId()).isEqualTo("t1");
        assertThat(taxed.getTaxCode()).isEqualTo("B");
        assertThat(taxed.getTaxAmount()).isEqualByComparingTo("16.00");
        assertThat(taxedAgain.getTaxAmount()).isEqualByComparingTo("8.00");
        // Untaxed and item-less lines keep their default zero and null rate.
        assertThat(untaxed.getTaxRateId()).isNull();
        assertThat(untaxed.getTaxAmount()).isEqualByComparingTo("0.00");
        assertThat(airtime.getTaxAmount()).isEqualByComparingTo("0.00");
        assertThat(total).isEqualByComparingTo("24.00");

        // The same item appears on two lines but is resolved once.
        verify(itemTaxResolver, times(1)).resolve("biz", "i1");
    }

    private static SaleItem saleItem(String itemId, String lineTotal) {
        SaleItem row = new SaleItem();
        row.setItemId(itemId);
        row.setLineTotal(new BigDecimal(lineTotal));
        return row;
    }
}
