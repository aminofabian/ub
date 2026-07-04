package zelisline.ub.inventory.restock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class RestockOrderPdfRendererTest {

    @Test
    void render_producesPdfHeader() {
        RestockOrderSnapshot snapshot =
                new RestockOrderSnapshot(
                        "Test Shop",
                        "Main",
                        "RST-20260704-ABCDEF12",
                        "04 Jul 2026",
                        "Supplier A",
                        "254700000000",
                        "buyer@test.com",
                        "Urgent shelf gap",
                        List.of(
                                new RestockOrderLineRow(
                                        "Milk 500ml",
                                        "SKU-1",
                                        new BigDecimal("12"),
                                        "1 piece",
                                        new BigDecimal("52"),
                                        new BigDecimal("624"),
                                        "Low shelf")),
                        new BigDecimal("624.00"));

        byte[] bytes = RestockOrderPdfRenderer.render(snapshot);
        assertThat(bytes).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }
}
