package zelisline.ub.marketplace.application;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

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

import zelisline.ub.marketplace.api.dto.SupplierPortalRestockBoardResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalRestockBoardResponse.SupplierPortalRestockDayBucket;
import zelisline.ub.marketplace.api.dto.SupplierPortalRestockBoardResponse.SupplierPortalRestockRow;

/** Printable restock run-sheet for daily / weekly suppliers. */
public final class SupplierPortalRestockBoardPdfRenderer {

    private SupplierPortalRestockBoardPdfRenderer() {
    }

    public static byte[] render(SupplierPortalRestockBoardResponse board) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4.rotate(), 28, 28, 32, 32);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15);
            Font section = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 8);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 7);

            doc.add(new Paragraph("Supplier Restock Plan", title));
            doc.add(new Paragraph(
                    "Window: " + board.window()
                            + " · " + board.windowStart() + " → " + board.windowEnd()
                            + " · Currency: " + board.currency()
                            + " · Generated: " + board.generatedAt(),
                    body));
            doc.add(new Paragraph(" "));

            var s = board.summary();
            doc.add(new Paragraph(
                    "Supplied: " + qty(s.suppliedQty())
                            + "   Till: " + qty(s.tillQty())
                            + "   Damages: " + qty(s.damageQty())
                            + "   On hand: " + qty(s.onHandQty())
                            + "   Suggested: " + qty(s.suggestedQty())
                            + "   Needs restock: " + s.needsRestockCount(),
                    section));
            doc.add(new Paragraph(" "));

            if (!board.daily().isEmpty()) {
                doc.add(new Paragraph("Daily movement", section));
                PdfPTable daily = new PdfPTable(4);
                daily.setWidthPercentage(55);
                daily.setWidths(new float[] {1.4f, 1.1f, 1.1f, 1.1f});
                daily.addCell(header("Date", section));
                daily.addCell(header("Supplied", section));
                daily.addCell(header("Till", section));
                daily.addCell(header("Damage", section));
                for (SupplierPortalRestockDayBucket d : board.daily()) {
                    daily.addCell(cell(d.date().toString(), body));
                    daily.addCell(cell(qty(d.suppliedQty()), body));
                    daily.addCell(cell(qty(d.tillQty()), body));
                    daily.addCell(cell(qty(d.damageQty()), body));
                }
                doc.add(daily);
                doc.add(new Paragraph(" "));
            }

            doc.add(new Paragraph("Restock lines (tick when loaded)", section));
            PdfPTable table = new PdfPTable(10);
            table.setWidthPercentage(100);
            table.setWidths(new float[] {2.4f, 1.6f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 1.0f, 0.8f, 0.7f});
            table.addCell(header("Product", section));
            table.addCell(header("Shop", section));
            table.addCell(header("Supplied", section));
            table.addCell(header("Till", section));
            table.addCell(header("Damage", section));
            table.addCell(header("On hand", section));
            table.addCell(header("Avg/day", section));
            table.addCell(header("Suggest", section));
            table.addCell(header("Urgency", section));
            table.addCell(header("Done", section));

            List<SupplierPortalRestockRow> rows = board.rows();
            if (rows.isEmpty()) {
                PdfPCell empty = cell("No product movement in this window.", body);
                empty.setColspan(10);
                table.addCell(empty);
            } else {
                for (SupplierPortalRestockRow row : rows) {
                    table.addCell(cell(row.productName(), body));
                    table.addCell(cell(row.shopName(), body));
                    table.addCell(cell(qty(row.suppliedQty()), body));
                    table.addCell(cell(row.velocityVisible() ? qty(row.tillQty()) : "—", body));
                    table.addCell(cell(qty(row.damageQty()), body));
                    table.addCell(cell(row.stockVisible() ? qty(row.onHand()) : "—", body));
                    table.addCell(cell(qty(row.avgDailyDemand()), body));
                    table.addCell(cell(qty(row.suggestedRestock()), section));
                    table.addCell(cell(row.urgency() != null ? row.urgency() : "", body));
                    table.addCell(cell("☐", small));
                }
            }
            doc.add(table);

            doc.add(new Paragraph(" "));
            doc.add(new Paragraph(
                    "Suggested qty targets ~"
                            + coverDaysLabel(board.window())
                            + " days of cover using till velocity when shared, else recent supply pace. "
                            + "On-hand and till columns stay blank unless the shop shares stock / velocity.",
                    small));

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render restock board PDF", e);
        }
    }

    private static String coverDaysLabel(String window) {
        if ("day".equalsIgnoreCase(window)) {
            return "3";
        }
        if ("month".equalsIgnoreCase(window)) {
            return "14";
        }
        return "7";
    }

    private static PdfPCell header(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setPadding(4f);
        return cell;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setPadding(3.5f);
        return c;
    }

    private static String qty(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
