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

public final class RestockOrderPdfRenderer {

    private RestockOrderPdfRenderer() {
    }

    public static byte[] render(RestockOrderSnapshot snapshot) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 48, 48, 56, 48);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font section = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 10);

            doc.add(new Paragraph("Supplier Restock Order", title));
            doc.add(new Paragraph(snapshot.businessName(), body));
            doc.add(new Paragraph("Branch: " + snapshot.branchName(), body));
            doc.add(new Paragraph("Order: " + snapshot.orderNumber(), body));
            doc.add(new Paragraph("Date: " + snapshot.orderDateDisplay(), body));
            doc.add(new Paragraph(" "));

            doc.add(new Paragraph("Supplier", section));
            doc.add(new Paragraph(snapshot.supplierName(), body));
            if (snapshot.supplierPhone() != null && !snapshot.supplierPhone().isBlank()) {
                doc.add(new Paragraph("Phone: " + snapshot.supplierPhone(), body));
            }
            if (snapshot.supplierEmail() != null && !snapshot.supplierEmail().isBlank()) {
                doc.add(new Paragraph("Email: " + snapshot.supplierEmail(), body));
            }
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(5);
            table.setWidthPercentage(100);
            table.setWidths(new float[] {3.2f, 1.2f, 1.4f, 1.2f, 1.4f});
            table.addCell(headerCell("Product", section));
            table.addCell(headerCell("Qty", section));
            table.addCell(headerCell("Pack", section));
            table.addCell(headerCell("Unit price", section));
            table.addCell(headerCell("Total", section));

            for (RestockOrderLineRow line : snapshot.lines()) {
                table.addCell(cell(line.itemName(), body));
                table.addCell(cell(formatQty(line.quantity()), body));
                table.addCell(cell(line.packLabel(), body));
                table.addCell(cell(formatMoney(line.unitPrice()), body));
                table.addCell(cell(formatMoney(line.lineTotal()), body));
            }
            doc.add(table);

            doc.add(new Paragraph(" "));
            doc.add(new Phrase("Supplier subtotal: KES ", section));
            doc.add(new Phrase(formatMoney(snapshot.supplierSubtotal()), section));

            if (snapshot.adminNotes() != null && !snapshot.adminNotes().isBlank()) {
                doc.add(new Paragraph(" "));
                doc.add(new Paragraph("Notes", section));
                doc.add(new Paragraph(snapshot.adminNotes(), body));
            }

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render restock order PDF", e);
        }
    }

    private static PdfPCell headerCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
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
            return "";
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    private static String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
