package zelisline.ub.sales.receipt;

import java.io.ByteArrayOutputStream;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

public final class ReceiptPdfRenderer {

    private ReceiptPdfRenderer() {
    }

    public static byte[] render(ReceiptSnapshot s) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A6, 40, 40, 48, 40);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
            Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 10);

            doc.add(new Paragraph(s.businessName(), title));
            doc.add(new Paragraph(s.branchName(), body));
            doc.add(new Paragraph("Sale " + s.saleId(), body));
            doc.add(new Paragraph(s.soldAtDisplay() + " · " + s.saleStatus(), body));
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(4);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{3f, 1.2f, 1.4f, 1.4f});
            for (ReceiptLineRow line : s.lines()) {
                table.addCell(cell(line.description(), body));
                table.addCell(cell(line.quantity(), body));
                table.addCell(cell(line.unitPrice(), body));
                table.addCell(cell(line.lineTotal(), body));
            }
            doc.add(table);

            doc.add(new Paragraph(" "));
            for (ReceiptPaymentRow p : s.payments()) {
                String ref = p.reference() != null && !p.reference().isBlank() ? " (" + p.reference() + ")" : "";
                doc.add(new Paragraph(p.method() + ": " + p.amount() + ref, body));
            }
            doc.add(new Paragraph(" "));
            doc.add(new Phrase("Total: ", bold));
            doc.add(new Phrase(s.grandTotalDisplay() + " " + s.currency(), bold));
            doc.add(new Paragraph(" "));
            if (s.footerNote() != null && !s.footerNote().isBlank()) {
                doc.add(new Paragraph(s.footerNote(), bold));
            }
            doc.add(new Paragraph("Thank you", body));

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render receipt PDF", e);
        }
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setBorder(0);
        c.setPadding(2);
        return c;
    }
}
