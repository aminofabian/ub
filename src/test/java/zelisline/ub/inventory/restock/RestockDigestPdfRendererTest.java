package zelisline.ub.inventory.restock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class RestockDigestPdfRendererTest {

    @Test
    void render_producesPdfHeader() {
        byte[] bytes = RestockDigestPdfRenderer.render(snapshot(
                "Dairy - Brookside",
                "Fresh Milk 500ml",
                "Sold 4/day - below min 12"));
        assertThat(bytes).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void render_doesNotThrowOnCharactersOutsideHelvetica() {
        byte[] bytes = RestockDigestPdfRenderer.render(snapshot(
                "Uncategorised — Tonight’s list",
                "Mũkimo ½kg “special” ™",
                "Sold 4/day · below min 12"));
        assertThat(bytes).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void pdfSafe_mapsSmartPunctuationAndDropsUnsupported() {
        String safe = RestockDigestPdfRenderer.pdfSafe("Tonight’s list — Dairy · Milk");
        assertThat(safe).startsWith("Tonight's list ");
        assertThat(safe).contains("Dairy");
        assertThat(safe).contains("Milk");
        assertThat(safe).contains("·");
        assertThat(RestockDigestPdfRenderer.pdfSafe("Mũkimo")).isEqualTo("M?kimo");
    }

    @Test
    void render_usesKioskOrderSheetCopy() {
        byte[] bytes = RestockDigestPdfRenderer.render(snapshot(
                "Brookside",
                "Fresh Milk 500ml",
                "Sold 4/day · below min 12"));
        String latin1 = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(latin1).contains("Kiosk.ke");
        assertThat(latin1).contains("Ksh");
        assertThat(latin1).contains("Total");
        assertThat(latin1).contains("PRODUCT");
        assertThat(latin1).contains("ON HAND");
        assertThat(latin1).doesNotContain("Tonight's list");
    }

    @Test
    void render_listsOnHandParAndQty() {
        byte[] bytes = RestockDigestPdfRenderer.render(snapshot(
                "Peter Mutua (Festive)",
                "Festive Bread 400g White",
                "Sold 6.5/day"));
        String latin1 = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(latin1).contains("Festive Bread");
        assertThat(latin1).contains("400g White");
        assertThat(latin1).contains("QTY");
        assertThat(latin1).contains("PAR");
        assertThat(latin1).contains("416.00");
    }

    private static RestockDigestPdfSnapshot snapshot(String title, String itemName, String evidence) {
        return new RestockDigestPdfSnapshot(
                "Palmart",
                "Mirema",
                "Sun 23 Aug 2026",
                title,
                "3 items",
                "KES",
                List.of(
                        new RestockDigestPdfLine(
                                itemName,
                                "SKU-1",
                                "Dairy",
                                "Brookside",
                                new BigDecimal("4"),
                                new BigDecimal("12"),
                                new BigDecimal("8"),
                                new BigDecimal("52"),
                                new BigDecimal("416"),
                                evidence)),
                new BigDecimal("416.00"));
    }
}
