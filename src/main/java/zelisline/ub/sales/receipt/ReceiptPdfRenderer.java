package zelisline.ub.sales.receipt;

import java.io.ByteArrayOutputStream;
import java.net.URI;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
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

            addLogoIfPresent(doc, s.logoUrl());
            doc.add(new Paragraph(s.businessName(), title));
            doc.add(new Paragraph(s.branchName(), body));
            doc.add(new Paragraph(s.receiptLabel(), body));
            doc.add(new Paragraph(s.soldAtDisplay() + " · " + s.saleStatus(), body));
            if (s.servedByName() != null && !s.servedByName().isBlank()) {
                doc.add(new Paragraph("Served by: " + s.servedByName(), body));
            }
            if (s.customerName() != null && !s.customerName().isBlank()) {
                doc.add(new Paragraph("Customer: " + s.customerName(), body));
            }
            if (s.customerPhone() != null && !s.customerPhone().isBlank()) {
                doc.add(new Paragraph("Phone: " + s.customerPhone(), body));
            }
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(4);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{3f, 1.2f, 1.4f, 1.4f});
            for (ReceiptLineRow line : s.lines()) {
                table.addCell(cell(line.description(), body));
                table.addCell(cell(formatQuantity(line), body));
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
            if (s.cashReceivedDisplay() != null && !s.cashReceivedDisplay().isBlank()) {
                doc.add(new Paragraph("Received: " + s.cashReceivedDisplay(), body));
                doc.add(new Paragraph(
                        "Change: " + (s.changeGivenDisplay() != null ? s.changeGivenDisplay() : "0.00"),
                        body));
                doc.add(new Paragraph(" "));
            }

            if (s.footerNote() != null && !s.footerNote().isBlank()) {
                doc.add(new Paragraph(s.footerNote(), bold));
            }

            addFooterContact(doc, s, body);

            if (s.branchReceiptMessage() != null && !s.branchReceiptMessage().isBlank()) {
                doc.add(new Paragraph(s.branchReceiptMessage(), body));
            }
            doc.add(new Paragraph("Thank you", body));

            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render receipt PDF", e);
        }
    }

    private static void addLogoIfPresent(Document doc, String logoUrl) throws Exception {
        if (logoUrl == null || logoUrl.isBlank()) {
            return;
        }
        try {
            Image img = Image.getInstance(URI.create(logoUrl.trim()).toURL());
            img.scaleToFit(120, 48);
            img.setAlignment(Element.ALIGN_CENTER);
            doc.add(img);
            doc.add(new Paragraph(" "));
        } catch (Exception ignored) {
            // Skip logo if URL is unreachable or invalid
        }
    }

    private static void addFooterContact(Document doc, ReceiptSnapshot s, Font body) throws DocumentException {
        doc.add(new Paragraph(" "));
        addIfPresent(doc, s.branchAddress(), body);
        addIfPresent(doc, formatContactLine("Tel", s.branchPhone()), body);
        addIfPresent(doc, formatContactLine("M-Pesa Till", s.tillNumber()), body);
        addIfPresent(doc, formatContactLine("Email", s.branchEmail()), body);
        addIfPresent(doc, formatContactLine("Web", s.branchWebsite()), body);
    }

    private static void addIfPresent(Document doc, String text, Font font) throws DocumentException {
        if (text != null && !text.isBlank()) {
            doc.add(new Paragraph(text, font));
        }
    }

    private static String formatContactLine(String label, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return label + ": " + value.trim();
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setBorder(0);
        c.setPadding(2);
        return c;
    }

    private static String formatQuantity(ReceiptLineRow line) {
        String qty = line.quantity();
        String unit = line.unitType();
        if (unit == null || unit.isBlank() || "each".equalsIgnoreCase(unit)) {
            return qty;
        }
        return qty + " " + unit;
    }
}
