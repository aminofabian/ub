package zelisline.ub.inventory.restock;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Closing-sheet PDF for one restock digest group. Built-in Helvetica is WinAnsi
 * only — grocery names can include characters outside that set, which used to
 * 500 the download. Strings are mapped to ASCII-safe text before drawing.
 */
public final class RestockDigestPdfRenderer {

    private RestockDigestPdfRenderer() {
    }

    public static byte[] render(RestockDigestPdfSnapshot snapshot) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 48, 48, 56, 48);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font section = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 8);

            doc.add(new Paragraph(pdfSafe("Tonight's list"), title));
            doc.add(new Paragraph(pdfSafe(snapshot.businessName())
                    + "  -  "
                    + pdfSafe(snapshot.branchName()), body));
            doc.add(new Paragraph(pdfSafe(snapshot.runDateDisplay()), small));
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph(pdfSafe(snapshot.groupTitle()), section));
            if (snapshot.groupHint() != null && !snapshot.groupHint().isBlank()) {
                doc.add(new Paragraph(pdfSafe(snapshot.groupHint()), small));
            }
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[] {3.4f, 1.1f, 1.0f, 1.0f, 1.0f, 1.2f, 1.3f});
            table.addCell(headerCell("Product", section));
            table.addCell(headerCell("On hand", section));
            table.addCell(headerCell("Par", section));
            table.addCell(headerCell("Qty", section));
            table.addCell(headerCell("Unit", section));
            table.addCell(headerCell("Total", section));
            table.addCell(headerCell("Why", section));

            for (RestockDigestPdfLine line : snapshot.lines()) {
                String product = pdfSafe(line.itemName());
                if (line.itemSku() != null && !line.itemSku().isBlank()) {
                    product = product + "\n" + pdfSafe(line.itemSku());
                }
                table.addCell(cell(product, body));
                table.addCell(cell(formatQty(line.onHand()), body));
                table.addCell(cell(formatQty(line.par()), body));
                table.addCell(cell(formatQty(line.quantity()), body));
                table.addCell(cell(formatMoney(line.unitCost(), snapshot.currency()), body));
                table.addCell(cell(formatMoney(line.lineTotal(), snapshot.currency()), body));
                table.addCell(cell(pdfSafe(line.evidence()), small));
            }
            doc.add(table);

            doc.add(new Paragraph(" "));
            Paragraph total = new Paragraph(
                    pdfSafe("Group total  " + formatMoney(snapshot.subtotal(), snapshot.currency())),
                    section);
            total.setAlignment(Element.ALIGN_RIGHT);
            doc.add(total);

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render restock digest PDF", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to render restock digest PDF", e);
        }
    }

    private static PdfPCell headerCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(pdfSafe(text), font));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setPadding(6f);
        return cell;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setPadding(5f);
        return c;
    }

    private static String formatQty(BigDecimal qty) {
        if (qty == null) {
            return "-";
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        if (amount == null) {
            return "-";
        }
        String code = currency == null || currency.isBlank() ? "KES" : pdfSafe(currency.trim());
        return code + " " + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Helvetica/WinAnsi cannot encode many grocery labels (Kikuyu vowels, smart
     * punctuation, symbols). Map the common ones and drop the rest so a single
     * product name cannot 500 the whole sheet.
     */
    static String pdfSafe(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\u00A0', '\u202F', '\u2007' -> out.append(' ');
                case '\u2013', '\u2014', '\u2212' -> out.append('-');
                case '\u00B7', '\u2022', '\u00B0' -> out.append('-');
                case '\u2018', '\u2019', '\u201A', '\u2032' -> out.append('\'');
                case '\u201C', '\u201D', '\u201E', '\u2033' -> out.append('"');
                case '\u2026' -> out.append("...");
                default -> {
                    if (c == '\n' || c == '\r' || c == '\t' || (c >= 32 && c <= 126) || (c >= 160 && c <= 255)) {
                        out.append(c);
                    } else if (Character.isHighSurrogate(c)) {
                        i++;
                        out.append('?');
                    } else {
                        out.append('?');
                    }
                }
            }
        }
        return out.toString();
    }
}
