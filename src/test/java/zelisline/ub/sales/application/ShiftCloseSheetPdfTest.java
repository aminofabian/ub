package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lowagie.text.pdf.PdfReader;

import zelisline.ub.sales.application.ShiftCloseSheetPdf.SaleLine;
import zelisline.ub.sales.application.ShiftCloseSheetPdf.Slip;

class ShiftCloseSheetPdfTest {

    @Test
    void stockSheetCarriesTheCategoryAndBothPrices() throws Exception {
        byte[] pdf = ShiftCloseSheetPdf.stock(
                "Prime",
                "Main",
                "21 Nov 2026",
                List.of(new ShiftCloseReportEmail.StockRow(
                        "Milk <500ml>",
                        new BigDecimal("24"),
                        new BigDecimal("85"),
                        new BigDecimal("110"),
                        "Dairy")),
                false);

        String page = pageText(pdf);
        assertThat(page).contains("Prime");
        assertThat(page).contains("DAIRY");
        assertThat(page).contains("Milk <500ml>");
        assertThat(page).contains("STOCK");
        assertThat(page).contains("BUYING");
        assertThat(page).contains("SELLING");
        assertThat(page).contains("24");
        assertThat(page).contains("110");
    }

    @Test
    void salesSheetListsTheReceiptAndTheDayTakings() throws Exception {
        byte[] pdf = ShiftCloseSheetPdf.sales(
                "Prime",
                "Main",
                "21 Nov 2026",
                3,
                new BigDecimal("12500"),
                List.of(new ShiftCloseReportEmail.Tender("mpesa", new BigDecimal("10000"))),
                List.of(new Slip(
                        "14:32    Receipt 12    Mpesa 10,000",
                        new BigDecimal("12500"),
                        List.of(new SaleLine("Rice 50kg", new BigDecimal("2"), new BigDecimal("12500"))))),
                false);

        String page = pageText(pdf);
        assertThat(page).contains("Today's sales");
        assertThat(page).contains("Receipt 12");
        assertThat(page).contains("Rice 50kg");
        assertThat(page).contains("Takings");
        assertThat(page).contains("12,500");
        assertThat(page).contains("Mpesa");
    }

    private static String pageText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new String(reader.getPageContent(1), StandardCharsets.ISO_8859_1);
        } finally {
            reader.close();
        }
    }
}
