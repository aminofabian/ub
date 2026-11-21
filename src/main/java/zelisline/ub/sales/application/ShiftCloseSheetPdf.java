package zelisline.ub.sales.application;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

import zelisline.ub.sales.application.ShiftCloseReportEmail.StockRow;
import zelisline.ub.sales.application.ShiftCloseReportEmail.Tender;

/**
 * The two sheets attached when a shift closes. Ivory paper, a forest rule,
 * a serif shop name, and the figures in a monospaced face.
 */
public final class ShiftCloseSheetPdf {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;
    private static final float MARGIN = 40f;
    private static final float FLOOR = 48f;
    private static final float PRICE_W = 76f;
    private static final float QTY_W = 58f;

    private static final Color PAPER = new Color(244, 241, 234);
    private static final Color INK = new Color(18, 26, 22);
    private static final Color MUTED = new Color(85, 97, 89);
    private static final Color FOREST = new Color(29, 75, 59);
    private static final Color HAIR = new Color(209, 204, 191);
    private static final Color RULE = new Color(191, 184, 168);

    private static final BaseFont SANS = font(BaseFont.HELVETICA);
    private static final BaseFont SANS_BOLD = font(BaseFont.HELVETICA_BOLD);
    private static final BaseFont SERIF = font(BaseFont.TIMES_BOLD);
    private static final BaseFont SERIF_ITALIC = font(BaseFont.TIMES_ITALIC);
    private static final BaseFont MONO = font(BaseFont.COURIER_BOLD);

    private ShiftCloseSheetPdf() {
    }

    public record SaleLine(String name, BigDecimal quantity, BigDecimal amount) {
    }

    /** One completed sale: the till line, then what was on it. */
    public record Slip(String heading, BigDecimal total, List<SaleLine> lines) {
    }

    public static byte[] stock(
            String shop, String branch, String day, List<StockRow> rows, boolean truncated) {
        List<String> meta = List.of(joinMeta(branch, day, rows.size() + " items", "KES"));
        return draw(
                shop,
                "Stock, buying and selling",
                meta,
                true,
                stockLines(rows, truncated),
                "Staff copy  -  buying prices stay with the owner");
    }

    public static byte[] sales(
            String shop,
            String branch,
            String day,
            int saleCount,
            BigDecimal total,
            List<Tender> tenders,
            List<Slip> slips,
            boolean truncated
    ) {
        String count = saleCount + (saleCount == 1 ? " sale" : " sales");
        List<String> meta = new ArrayList<>();
        meta.add(joinMeta(branch, day, count, "KES " + ShiftCloseReportEmail.money(total)));
        String paid = tenderLine(tenders);
        if (!paid.isEmpty()) {
            meta.add(paid);
        }
        return draw(
                shop,
                "Today's sales",
                meta,
                false,
                salesLines(slips, total, truncated),
                "Completed sales at this branch. Voids are left out.");
    }

    private static List<Line> stockLines(List<StockRow> rows, boolean truncated) {
        List<Line> lines = new ArrayList<>();
        String category = null;
        for (StockRow row : rows) {
            String next = categoryOf(row);
            if (!next.equals(category)) {
                lines.add(Line.category(next.toUpperCase(Locale.ROOT)));
                category = next;
            }
            lines.add(Line.item(
                    row.name(),
                    ShiftCloseReportEmail.qty(row.stock()),
                    ShiftCloseReportEmail.money(row.buyPrice()),
                    ShiftCloseReportEmail.money(row.sellPrice())));
        }
        if (rows.isEmpty()) {
            lines.add(Line.note("No stocked items at this branch."));
        }
        if (truncated) {
            lines.add(Line.note("Showing the first rows. The rest is not in this PDF."));
        }
        return lines;
    }

    private static List<Line> salesLines(List<Slip> slips, BigDecimal total, boolean truncated) {
        List<Line> lines = new ArrayList<>();
        if (slips.isEmpty()) {
            lines.add(Line.note("Nothing completed yet today."));
        }
        for (Slip slip : slips) {
            lines.add(Line.receipt(slip.heading(), ShiftCloseReportEmail.money(slip.total())));
            for (SaleLine item : slip.lines()) {
                lines.add(Line.item(
                        item.name(),
                        ShiftCloseReportEmail.qty(item.quantity()),
                        ShiftCloseReportEmail.money(item.amount()),
                        ""));
            }
        }
        lines.add(Line.total("Takings", ShiftCloseReportEmail.money(total)));
        if (truncated) {
            lines.add(Line.note("Showing the first receipts. The rest of the day is not in this PDF."));
        }
        return lines;
    }

    private static String categoryOf(StockRow row) {
        if (row.category() == null || row.category().isBlank()) {
            return "Other";
        }
        return row.category().trim();
    }

    private static byte[] draw(
            String shop, String title, List<String> meta, boolean four, List<Line> lines, String footer) {
        try {
            List<List<Line>> pages = paginate(lines, room(meta.size(), true), room(meta.size(), false));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 0, 0, 0, 0);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.open();
            for (int index = 0; index < pages.size(); index += 1) {
                if (index > 0) {
                    doc.newPage();
                }
                paintPage(writer.getDirectContent(), shop, title, meta, four, pages.get(index),
                        footer, index == 0, index + 1, pages.size());
            }
            doc.close();
            return out.toByteArray();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to render shift close PDF", ex);
        }
    }

    private static void paintPage(
            PdfContentByte cb,
            String shop,
            String title,
            List<String> meta,
            boolean four,
            List<Line> lines,
            String footer,
            boolean first,
            int pageNo,
            int pageCount
    ) {
        float top = paintMast(cb, shop, title, meta, first);
        top = paintHeads(cb, top, four);
        for (Line line : lines) {
            top = paintLine(cb, top, line, four);
        }
        paintFooter(cb, footer, pageNo, pageCount);
    }

    private static float paintMast(PdfContentByte cb, String shop, String title, List<String> meta, boolean first) {
        cb.setColorFill(PAPER);
        cb.rectangle(0, 0, PAGE_W, PAGE_H);
        cb.fill();
        if (!first) {
            text(cb, MARGIN, PAGE_H - 28f, shop, SERIF, 11f, INK);
            textRight(cb, PAGE_W - MARGIN, PAGE_H - 28f, title, SERIF_ITALIC, 10f, FOREST);
            rule(cb, PAGE_H - 36f, FOREST, 0.6f);
            return PAGE_H - 36f;
        }
        float y = PAGE_H - 48f;
        text(cb, MARGIN, y, shop, SERIF, 16f, INK);
        y -= 18f;
        text(cb, MARGIN, y, title, SERIF_ITALIC, 12f, FOREST);
        y -= 16f;
        for (String line : meta) {
            text(cb, MARGIN, y, line, SANS, 8.5f, MUTED);
            y -= 12f;
        }
        y -= 4f;
        rule(cb, y, FOREST, 0.7f);
        return y;
    }

    private static float paintHeads(PdfContentByte cb, float top, boolean four) {
        float y = top - 14f;
        text(cb, MARGIN, y, "ITEM", SANS_BOLD, 7f, MUTED);
        if (four) {
            textRight(cb, stockRight(), y, "STOCK", SANS_BOLD, 7f, MUTED);
            textRight(cb, buyRight(), y, "BUYING", SANS_BOLD, 7f, MUTED);
            textRight(cb, sellRight(), y, "SELLING", SANS_BOLD, 7f, MUTED);
        } else {
            textRight(cb, qtyRight(), y, "QTY", SANS_BOLD, 7f, MUTED);
            textRight(cb, amountRight(), y, "AMOUNT", SANS_BOLD, 7f, MUTED);
        }
        rule(cb, y - 5f, RULE, 0.4f);
        return y - 8f;
    }

    private static float paintLine(PdfContentByte cb, float top, Line line, boolean four) {
        float baseline = top - 12f;
        if (line.kind == Kind.CATEGORY) {
            text(cb, MARGIN, baseline, line.name, SANS_BOLD, 8f, FOREST);
            rule(cb, top - height(line) + 3f, RULE, 0.4f);
        } else if (line.kind == Kind.RECEIPT) {
            text(cb, MARGIN, baseline, line.name, SANS_BOLD, 9f, FOREST);
            textRight(cb, amountRight(), baseline, line.b, MONO, 9f, INK);
        } else if (line.kind == Kind.TOTAL) {
            rule(cb, top - 2f, FOREST, 0.6f);
            text(cb, MARGIN, baseline - 4f, line.name, SERIF_ITALIC, 11f, FOREST);
            textRight(cb, rightEdge(four), baseline - 4f, line.b, MONO, 10f, INK);
        } else if (line.kind == Kind.NOTE) {
            text(cb, MARGIN, baseline, line.name, SANS, 8.5f, MUTED);
        } else {
            paintItem(cb, baseline, line, four);
            rule(cb, top - height(line) + 1f, HAIR, 0.3f);
        }
        return top - height(line);
    }

    private static void paintItem(PdfContentByte cb, float baseline, Line line, boolean four) {
        float nameRight = (four ? stockRight() - QTY_W : qtyRight() - 72f) - 12f;
        text(cb, MARGIN + (four ? 0f : 8f), baseline, clip(line.name, SANS, 9f, nameRight - MARGIN), SANS, 9f, INK);
        if (four) {
            textRight(cb, stockRight(), baseline, line.a, MONO, 9f, INK);
            textRight(cb, buyRight(), baseline, line.b, MONO, 9f, MUTED);
            textRight(cb, sellRight(), baseline, line.c, MONO, 9f, INK);
            return;
        }
        textRight(cb, qtyRight(), baseline, line.a, MONO, 9f, INK);
        textRight(cb, amountRight(), baseline, line.b, MONO, 9f, INK);
    }

    private static void paintFooter(PdfContentByte cb, String note, int pageNo, int pageCount) {
        rule(cb, 34f, FOREST, 0.5f);
        text(cb, MARGIN, 20f, note, SANS, 7.5f, MUTED);
        textRight(cb, PAGE_W - MARGIN, 20f, pageNo + "  /  " + pageCount, MONO, 8f, MUTED);
    }

    private static List<List<Line>> paginate(List<Line> lines, float firstRoom, float nextRoom) {
        List<List<Line>> pages = new ArrayList<>();
        List<Line> page = new ArrayList<>();
        float used = 0f;
        float room = firstRoom;
        for (Line line : lines) {
            float h = height(line);
            if (!page.isEmpty() && used + h > room) {
                pages.add(page);
                page = new ArrayList<>();
                used = 0f;
                room = nextRoom;
            }
            page.add(line);
            used += h;
        }
        if (!page.isEmpty() || pages.isEmpty()) {
            pages.add(page);
        }
        return pages;
    }

    private static float room(int metas, boolean first) {
        float mast = first ? 108f + metas * 12f : 58f;
        return PAGE_H - mast - FLOOR - 8f;
    }

    private static float height(Line line) {
        if (line.kind == Kind.CATEGORY || line.kind == Kind.RECEIPT) {
            return 20f;
        }
        if (line.kind == Kind.TOTAL) {
            return 24f;
        }
        return 16f;
    }

    private static float sellRight() {
        return PAGE_W - MARGIN;
    }

    private static float buyRight() {
        return sellRight() - PRICE_W;
    }

    private static float stockRight() {
        return buyRight() - QTY_W;
    }

    private static float amountRight() {
        return PAGE_W - MARGIN;
    }

    private static float qtyRight() {
        return amountRight() - 72f;
    }

    private static float rightEdge(boolean four) {
        return four ? sellRight() : amountRight();
    }

    private static String joinMeta(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append("   -   ");
            }
            out.append(part.trim());
        }
        return out.toString();
    }

    private static String tenderLine(List<Tender> tenders) {
        StringBuilder out = new StringBuilder();
        if (tenders == null) {
            return "";
        }
        for (Tender tender : tenders) {
            if (out.length() > 0) {
                out.append("   -   ");
            }
            out.append(ShiftCloseReportEmail.tenderLabel(tender.method()))
                    .append(' ')
                    .append(ShiftCloseReportEmail.money(tender.amount()));
        }
        return out.toString();
    }

    private static void rule(PdfContentByte cb, float y, Color color, float width) {
        cb.setColorStroke(color);
        cb.setLineWidth(width);
        cb.moveTo(MARGIN, y);
        cb.lineTo(PAGE_W - MARGIN, y);
        cb.stroke();
    }

    private static void text(
            PdfContentByte cb, float x, float y, String value, BaseFont face, float size, Color color) {
        cb.beginText();
        cb.setFontAndSize(face, size);
        cb.setColorFill(color);
        cb.setTextMatrix(x, y);
        cb.showText(safe(value));
        cb.endText();
    }

    private static void textRight(
            PdfContentByte cb, float xRight, float y, String value, BaseFont face, float size, Color color) {
        String clean = safe(value);
        text(cb, xRight - face.getWidthPoint(clean, size), y, clean, face, size, color);
    }

    private static String clip(String value, BaseFont face, float size, float maxWidth) {
        String clean = safe(value).replaceAll("\\s+", " ").trim();
        if (face.getWidthPoint(clean, size) <= maxWidth) {
            return clean;
        }
        String sliced = clean;
        while (sliced.length() > 1 && face.getWidthPoint(sliced + "...", size) > maxWidth) {
            sliced = sliced.substring(0, sliced.length() - 1);
        }
        return sliced + "...";
    }

    private static BaseFont font(String name) {
        try {
            return BaseFont.createFont(name, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 32 && c <= 126) {
                out.append(c);
            } else if (c == '\u2013' || c == '\u2014' || c == '\u00B7') {
                out.append('-');
            } else {
                out.append('?');
            }
        }
        return out.toString();
    }

    private enum Kind {
        CATEGORY, ITEM, RECEIPT, TOTAL, NOTE
    }

    private record Line(Kind kind, String name, String a, String b, String c) {
        static Line category(String name) {
            return new Line(Kind.CATEGORY, name, "", "", "");
        }

        static Line item(String name, String a, String b, String c) {
            return new Line(Kind.ITEM, name, a, b, c);
        }

        static Line receipt(String heading, String total) {
            return new Line(Kind.RECEIPT, heading, "", total, "");
        }

        static Line total(String label, String amount) {
            return new Line(Kind.TOTAL, label, "", amount, "");
        }

        static Line note(String text) {
            return new Line(Kind.NOTE, text, "", "", "");
        }
    }
}
