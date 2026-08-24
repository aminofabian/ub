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
        assertThat(RestockDigestPdfRenderer.pdfSafe("Tonight’s list — Dairy · Milk"))
                .isEqualTo("Tonight's list - Dairy - Milk");
        assertThat(RestockDigestPdfRenderer.pdfSafe("Mũkimo")).isEqualTo("M?kimo");
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
