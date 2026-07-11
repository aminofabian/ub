package zelisline.ub.sales.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReceiptRendererTest {

    private static ReceiptSnapshot sampleSnapshot(List<ReceiptLineRow> lines) {
        return new ReceiptSnapshot(
                "Butcher Shop",
                null,
                "Main Branch",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "KES",
                "sale-123",
                42L,
                "2026-07-01 12:00 UTC",
                "completed",
                lines,
                List.of(new ReceiptPaymentRow("cash", "416.40", null)),
                "416.40",
                ""
        );
    }

    @Test
    void escPos_weighedLine_showsKgUnit() {
        ReceiptSnapshot s = sampleSnapshot(List.of(
                new ReceiptLineRow("Beef Mince", "0.347", "kg", "1200.00", "416.40")
        ));

        byte[] bytes = ReceiptEscPosRenderer.render(s, 58);
        String text = new String(bytes, StandardCharsets.US_ASCII);

        assertThat(text).contains("0.347 kg x 1200.00 = 416.40");
        assertThat(text).contains("Receipt #42");
    }

    @Test
    void escPos_nonWeighedLine_keepsIntegerFormat() {
        ReceiptSnapshot s = sampleSnapshot(List.of(
                new ReceiptLineRow("Soda", "2", "each", "5.00", "10.00")
        ));

        byte[] bytes = ReceiptEscPosRenderer.render(s, 58);
        String text = new String(bytes, StandardCharsets.US_ASCII);

        assertThat(text).contains("2 x 5.00 = 10.00");
        assertThat(text).doesNotContain("2 each");
    }

    @Test
    void escPos_nullUnitType_keepsIntegerFormat() {
        ReceiptSnapshot s = sampleSnapshot(List.of(
                new ReceiptLineRow("Soda", "2", null, "5.00", "10.00")
        ));

        byte[] bytes = ReceiptEscPosRenderer.render(s, 58);
        String text = new String(bytes, StandardCharsets.US_ASCII);

        assertThat(text).contains("2 x 5.00 = 10.00");
    }

    @Test
    void escPos_endsWithFeedAndPartialCut() {
        ReceiptSnapshot s = sampleSnapshot(List.of(
                new ReceiptLineRow("Soda", "1", "each", "5.00", "5.00")
        ));

        byte[] bytes = ReceiptEscPosRenderer.render(s, 58);

        // ESC d 8 then GS V 1 (partial cut) — matches Caysn CN811-UB
        assertThat(bytes).endsWith(new byte[]{0x1B, 0x64, 0x08, 0x1D, 0x56, 0x01});
    }

    @Test
    void pdf_weighedLine_renders() {
        ReceiptSnapshot s = sampleSnapshot(List.of(
                new ReceiptLineRow("Beef Mince", "0.347", "kg", "1200.00", "416.40")
        ));

        byte[] bytes = ReceiptPdfRenderer.render(s);

        assertThat(bytes).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }
}
