package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class ShiftCloseReportEmailTest {

    @Test
    void stockLetterCarriesQuantityAndBothPrices() {
        ShiftCloseReportEmail.Letter letter = ShiftCloseReportEmail.stock(
                "Prime",
                "Main",
                "21 Nov 2026",
                List.of(new ShiftCloseReportEmail.StockRow(
                        "Milk <500ml>",
                        new BigDecimal("24"),
                        new BigDecimal("85"),
                        new BigDecimal("110"))),
                0);

        assertThat(letter.subject()).isEqualTo("Prime stock at shift close");
        assertThat(letter.text()).contains("Milk <500ml>");
        assertThat(letter.text()).contains("24");
        assertThat(letter.text()).contains("85");
        assertThat(letter.text()).contains("110");
        assertThat(letter.html()).contains("Milk &lt;500ml&gt;");
        assertThat(letter.html()).contains("Buying");
        assertThat(letter.html()).contains("Selling");
        assertThat(letter.html()).doesNotContain("Milk <500ml>");
    }

    @Test
    void salesLetterCarriesTheDayTotalAndLines() {
        ShiftCloseReportEmail.Letter letter = ShiftCloseReportEmail.sales(
                "Prime",
                "Main",
                "21 Nov 2026",
                3,
                new BigDecimal("12500"),
                List.of(new ShiftCloseReportEmail.Tender("mpesa", new BigDecimal("10000"))),
                List.of(new ShiftCloseReportEmail.SaleRow(
                        "Rice 50kg", new BigDecimal("2"), new BigDecimal("12500"))),
                4);

        assertThat(letter.subject()).isEqualTo("Prime sales today");
        assertThat(letter.text()).contains("3 sales");
        assertThat(letter.text()).contains("12,500");
        assertThat(letter.text()).contains("Mpesa");
        assertThat(letter.text()).contains("Rice 50kg");
        assertThat(letter.text()).contains("The rest is not in this email");
        assertThat(letter.html()).contains("Shifts &amp; cash drawer");
    }

    @Test
    void coverNamesBothReports() {
        ShiftCloseReportEmail.Letter letter = ShiftCloseReportEmail.cover(
                "Prime",
                "Main",
                "21 Nov 2026",
                3,
                new BigDecimal("12500"),
                List.of(new ShiftCloseReportEmail.Tender("mpesa", new BigDecimal("10000"))));

        assertThat(letter.subject()).isEqualTo("Prime shift closed");
        assertThat(letter.text()).contains("3 sales");
        assertThat(letter.text()).contains("receipt by receipt");
        assertThat(letter.text()).contains("Mpesa");
        assertThat(letter.html()).contains("Shifts &amp; cash drawer");
        assertThat(letter.html()).doesNotContain("<table");
    }
}
