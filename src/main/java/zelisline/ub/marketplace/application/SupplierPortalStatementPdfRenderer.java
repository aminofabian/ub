package zelisline.ub.marketplace.application;

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

import zelisline.ub.marketplace.api.dto.SupplierPortalLedgerEntry;
import zelisline.ub.marketplace.api.dto.SupplierPortalStatementResponse;

public final class SupplierPortalStatementPdfRenderer {

    private SupplierPortalStatementPdfRenderer() {
    }

    public static byte[] render(SupplierPortalStatementResponse statement) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4.rotate(), 36, 36, 40, 40);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font section = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 9);

            doc.add(new Paragraph("Supplier Statement", title));
            doc.add(new Paragraph(statement.shopName(), body));
            doc.add(new Paragraph(
                    "Period: " + statement.periodStart() + " → " + statement.periodEnd()
                            + " · Currency: " + statement.currency(),
                    body));
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph(
                    "Opening: " + money(statement.openingBalance())
                            + "   Invoices: " + money(statement.periodInvoices())
                            + "   Payments: " + money(statement.periodPayments())
                            + "   Closing: " + money(statement.closingBalance()),
                    section));
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[] {1.2f, 1.1f, 1.6f, 2.6f, 1.1f, 1.1f, 1.2f});
            table.addCell(header("Date", section));
            table.addCell(header("Type", section));
            table.addCell(header("Reference", section));
            table.addCell(header("Description", section));
            table.addCell(header("Debit", section));
            table.addCell(header("Credit", section));
            table.addCell(header("Balance", section));

            table.addCell(cell(statement.periodStart().toString(), body));
            table.addCell(cell("OPENING", body));
            table.addCell(cell("", body));
            table.addCell(cell("Opening balance", body));
            table.addCell(cell("", body));
            table.addCell(cell("", body));
            table.addCell(cell(money(statement.openingBalance()), body));

            for (SupplierPortalLedgerEntry e : statement.entries()) {
                table.addCell(cell(e.date().toString(), body));
                table.addCell(cell(e.type(), body));
                table.addCell(cell(e.reference(), body));
                table.addCell(cell(e.description(), body));
                table.addCell(cell(zeroBlank(e.debit()), body));
                table.addCell(cell(zeroBlank(e.credit()), body));
                table.addCell(cell(money(e.balance()), body));
            }

            table.addCell(cell(statement.periodEnd().toString(), body));
            table.addCell(cell("CLOSING", body));
            table.addCell(cell("", body));
            table.addCell(cell("Closing balance", body));
            table.addCell(cell("", body));
            table.addCell(cell("", body));
            table.addCell(cell(money(statement.closingBalance()), body));

            doc.add(table);
            doc.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render supplier statement PDF", e);
        }
    }

    private static PdfPCell header(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setPadding(5f);
        return cell;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setPadding(4f);
        return c;
    }

    private static String money(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String zeroBlank(BigDecimal amount) {
        if (amount == null || amount.signum() == 0) {
            return "";
        }
        return money(amount);
    }
}
