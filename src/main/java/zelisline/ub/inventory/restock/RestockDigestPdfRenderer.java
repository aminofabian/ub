package zelisline.ub.inventory.restock;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Restock digest PDF in the Kiosk.ke marketplace order-sheet language: sage
 * paper, forest hero, Times name, mango totals, dashed leaders.
 *
 * Built-in Type1 fonts are WinAnsi only — grocery names are mapped before draw.
 */
public final class RestockDigestPdfRenderer {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;
    private static final float MARGIN = 32f;
    private static final float CONTENT_W = PAGE_W - MARGIN * 2;
    private static final float ROW_H = 22f;
    private static final float FOOTER_H = 48f;

    private static final Color INK = rgb(0x24312A);
    private static final Color INK_SOFT = rgb(0x5C6A5F);
    private static final Color PAPER = rgb(0xEFF2EC);
    private static final Color PAPER_RAISED = rgb(0xF8FAF6);
    private static final Color LINE = rgb(0xD8DECE);
    private static final Color FOREST = rgb(0x2F5233);
    private static final Color FOREST_DEEP = rgb(0x1E3B26);
    private static final Color MANGO = rgb(0xB9691A);
    private static final Color TOMATO = rgb(0xC1452B);
    private static final Color HERO_MUTED = rgb(0xCBD8C4);
    private static final Color EYEBROW = rgb(0xB9C9B4);
    private static final Color PILL_INK = rgb(0xE7EEE2);
    private static final Color PILL_FILL = rgb(0x335938);
    private static final Color WHITE = Color.WHITE;

    private static final BaseFont HELVETICA;
    private static final BaseFont HELVETICA_BOLD;
    private static final BaseFont COURIER_BOLD;
    private static final BaseFont TIMES_BOLD;

    static {
        try {
            HELVETICA = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            HELVETICA_BOLD = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            COURIER_BOLD = BaseFont.createFont(BaseFont.COURIER_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            TIMES_BOLD = BaseFont.createFont(BaseFont.TIMES_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private RestockDigestPdfRenderer() {
    }

    public static byte[] render(RestockDigestPdfSnapshot snapshot) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 0, 0, 0, 0);
            PdfWriter writer = PdfWriter.getInstance(doc, baos);
            writer.setCompressionLevel(0);
            doc.open();

            List<RestockDigestPdfLine> lines = snapshot.lines() == null ? List.of() : snapshot.lines();
            int items = lines.size();
            BigDecimal units = BigDecimal.ZERO;
            for (RestockDigestPdfLine line : lines) {
                if (line.quantity() != null) {
                    units = units.add(line.quantity());
                }
            }
            Context ctx = new Context(
                    pdfSafe(nvl(snapshot.groupTitle())),
                    pdfSafe(nvl(snapshot.branchName())),
                    pdfSafe(nvl(snapshot.businessName())),
                    pdfSafe(nvl(snapshot.runDateDisplay())),
                    items,
                    formatQty(units),
                    kshLabel(snapshot.currency()),
                    snapshot.subtotal());
            int pageCount = countPages(ctx, lines);

            PdfContentByte cb = writer.getDirectContent();
            int pageNo = 1;
            float cursor = paintHero(cb, ctx, true);

            for (RestockDigestPdfLine line : lines) {
                if (cursor - ROW_H < FOOTER_H + 40f) {
                    paintFooter(cb, ctx, pageNo, pageCount);
                    doc.newPage();
                    pageNo++;
                    cursor = paintHero(cb, ctx, false);
                }
                cursor = drawRow(cb, cursor, line, ctx.currencyLabel);
            }

            if (cursor < FOOTER_H + 70f) {
                paintFooter(cb, ctx, pageNo, pageCount);
                doc.newPage();
                pageNo++;
                cursor = paintHero(cb, ctx, false);
            }
            drawTotal(cb, cursor, ctx, items, units);
            paintFooter(cb, ctx, pageNo, pageCount);

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render restock digest PDF", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to render restock digest PDF", e);
        }
    }

    private static int countPages(Context ctx, List<RestockDigestPdfLine> lines) {
        int pages = 1;
        float cursor = PAGE_H - heroDrop(ctx) - 32f;
        for (int i = 0; i < lines.size(); i++) {
            if (cursor - ROW_H < FOOTER_H + 40f) {
                pages++;
                cursor = PAGE_H - 72f;
            }
            cursor -= ROW_H;
        }
        if (cursor < FOOTER_H + 70f) {
            pages++;
        }
        return pages;
    }

    private static float heroDrop(Context ctx) {
        List<String> nameLines = wrapText(ctx.title, TIMES_BOLD, 22, CONTENT_W, 2);
        float drop = 28f;
        if (!ctx.location.isBlank()) {
            drop += 16f;
        }
        drop += nameLines.size() * 24f;
        if (!ctx.listedBy.isBlank()) {
            drop += 16f;
        }
        drop += 32f;
        drop += 32f;
        return drop;
    }

    private static float paintHero(PdfContentByte cb, Context ctx, boolean first) {
        cb.setColorFill(PAPER);
        cb.rectangle(0, 0, PAGE_W, PAGE_H);
        cb.fill();

        if (!first) {
            cb.setColorFill(FOREST_DEEP);
            cb.rectangle(0, PAGE_H - 44, PAGE_W, 44);
            cb.fill();
            text(cb, MARGIN, PAGE_H - 28, ctx.title, TIMES_BOLD, 12, WHITE);
            textRight(cb, PAGE_W - MARGIN, PAGE_H - 28, "Order (continued)", HELVETICA, 9, HERO_MUTED);
            paintColumnHeads(cb, PAGE_H - 58);
            return PAGE_H - 72;
        }

        List<String> nameLines = wrapText(ctx.title, TIMES_BOLD, 22, CONTENT_W, 2);
        float drop = heroDrop(ctx);

        cb.setColorFill(FOREST_DEEP);
        cb.rectangle(0, PAGE_H - drop, PAGE_W, drop);
        cb.fill();

        float y = PAGE_H - 28f;
        if (!ctx.location.isBlank()) {
            text(cb, MARGIN, y, ctx.location.toUpperCase(), COURIER_BOLD, 8, EYEBROW);
            y -= 16f;
        }
        for (String line : nameLines) {
            text(cb, MARGIN, y, line, TIMES_BOLD, 22, PAPER_RAISED);
            y -= 24f;
        }
        if (!ctx.listedBy.isBlank()) {
            text(cb, MARGIN, y, ctx.listedBy, HELVETICA, 10, HERO_MUTED);
            y -= 16f;
        }

        String[] pills = {
                ctx.items + (ctx.items == 1 ? " item" : " items"),
                ctx.units + ("1".equals(ctx.units) ? " unit" : " units"),
                ctx.dateShort
        };
        float px = MARGIN;
        for (String pill : pills) {
            float w = width(COURIER_BOLD, pill, 8) + 16f;
            roundFill(cb, px, y - 14f, w, 16f, 8f, PILL_FILL);
            text(cb, px + 8f, y - 10f, pill, COURIER_BOLD, 8, PILL_INK);
            px += w + 6f;
        }

        cb.setColorFill(FOREST);
        cb.rectangle(0, PAGE_H - drop, PAGE_W, 32);
        cb.fill();
        text(cb, MARGIN, PAGE_H - drop + 12f, "Tonight's list", COURIER_BOLD, 13, WHITE);
        textRight(
                cb,
                PAGE_W - MARGIN,
                PAGE_H - drop + 12f,
                "Confirm quantities, then order",
                HELVETICA,
                8,
                HERO_MUTED);

        float headsY = PAGE_H - drop - 18f;
        paintColumnHeads(cb, headsY);
        return headsY - 14f;
    }

    private static void paintColumnHeads(PdfContentByte cb, float y) {
        text(cb, MARGIN, y, "ITEM", HELVETICA_BOLD, 7.5f, INK_SOFT);
        textRight(cb, PAGE_W - MARGIN, y, "TOTAL", HELVETICA_BOLD, 7.5f, INK_SOFT);
        stroke(cb, MARGIN, y - 5f, PAGE_W - MARGIN, y - 5f, LINE, 0.4f);
    }

    private static float drawRow(PdfContentByte cb, float cursorY, RestockDigestPdfLine line, String currencyLabel) {
        float y = cursorY - ROW_H;
        String qty = "\u00D7 " + formatQty(line.quantity());
        boolean ask = line.lineTotal() == null;
        String price = ask ? "Ask" : ksh(line.lineTotal(), currencyLabel);
        float priceW = width(COURIER_BOLD, price, 10);
        float qtyW = width(HELVETICA, qty, 9);
        float nameMax = CONTENT_W - priceW - qtyW - 28f;
        String name = truncate(pdfSafe(nvl(line.itemName())), HELVETICA_BOLD, 10, nameMax);

        text(cb, MARGIN, y + 6f, name, HELVETICA_BOLD, 10, INK);
        float nameEnd = MARGIN + width(HELVETICA_BOLD, name, 10);
        text(cb, nameEnd + 6f, y + 6f, qty, HELVETICA, 9, INK_SOFT);
        float qtyEnd = nameEnd + 6f + qtyW;
        dashLine(cb, qtyEnd + 6f, y + 8f, PAGE_W - MARGIN - priceW - 8f, y + 8f, LINE);
        textRight(cb, PAGE_W - MARGIN, y + 6f, price, COURIER_BOLD, 10, ask ? TOMATO : MANGO);
        stroke(cb, MARGIN, y, PAGE_W - MARGIN, y, LINE, 0.3f);
        return y;
    }

    private static void drawTotal(
            PdfContentByte cb,
            float cursorY,
            Context ctx,
            int items,
            BigDecimal units
    ) {
        float y = cursorY - 36f;
        roundFill(cb, MARGIN, y, CONTENT_W, 32f, 6f, FOREST_DEEP);
        text(cb, MARGIN + 12f, y + 12f, "TOTAL", HELVETICA_BOLD, 9, HERO_MUTED);
        boolean priced = ctx.subtotal != null;
        textRight(
                cb,
                PAGE_W - MARGIN - 12f,
                y + 11f,
                priced ? ksh(ctx.subtotal, ctx.currencyLabel) : "Ask",
                COURIER_BOLD,
                13,
                WHITE);
        String summary = items + (items == 1 ? " item" : " items")
                + " \u00B7 "
                + formatQty(units)
                + (units.compareTo(BigDecimal.ONE) == 0 ? " unit" : " units");
        text(cb, MARGIN, y - 14f, pdfSafe(summary), COURIER_BOLD, 8, INK_SOFT);
    }

    private static void paintFooter(PdfContentByte cb, Context ctx, int pageNo, int of) {
        stroke(cb, MARGIN, 32f, PAGE_W - MARGIN, 32f, LINE, 0.3f);
        text(
                cb,
                MARGIN,
                20f,
                "Kiosk.ke · Please confirm availability and pricing.",
                HELVETICA,
                8,
                INK_SOFT);
        String right = ctx.dateShort + "  ·  " + pageNo + " / " + of;
        textRight(cb, PAGE_W - MARGIN, 20f, pdfSafe(right), COURIER_BOLD, 8, INK_SOFT);
    }

    private static void text(PdfContentByte cb, float x, float y, String value, BaseFont font, float size, Color color) {
        String safe = pdfSafe(value);
        if (safe.isEmpty()) {
            return;
        }
        cb.beginText();
        cb.setFontAndSize(font, size);
        cb.setColorFill(color);
        cb.setTextMatrix(x, y);
        cb.showText(safe);
        cb.endText();
    }

    private static void textRight(
            PdfContentByte cb,
            float xRight,
            float y,
            String value,
            BaseFont font,
            float size,
            Color color
    ) {
        String safe = pdfSafe(value);
        text(cb, xRight - width(font, safe, size), y, safe, font, size, color);
    }

    private static void stroke(PdfContentByte cb, float x1, float y1, float x2, float y2, Color color, float width) {
        cb.setLineWidth(width);
        cb.setColorStroke(color);
        cb.moveTo(x1, y1);
        cb.lineTo(x2, y2);
        cb.stroke();
    }

    private static void dashLine(PdfContentByte cb, float x1, float y1, float x2, float y2, Color color) {
        if (x2 - x1 < 8f) {
            return;
        }
        cb.setLineDash(0.7f, 1.5f, 0f);
        cb.setLineWidth(0.4f);
        cb.setColorStroke(color);
        cb.moveTo(x1, y1);
        cb.lineTo(x2, y2);
        cb.stroke();
        cb.setLineDash(1f);
    }

    private static void roundFill(PdfContentByte cb, float x, float y, float w, float h, float r, Color color) {
        cb.setColorFill(color);
        cb.roundRectangle(x, y, w, h, Math.min(r, Math.min(w, h) / 2f));
        cb.fill();
    }

    private static float width(BaseFont font, String text, float size) {
        return font.getWidthPoint(pdfSafe(text), size);
    }

    private static List<String> wrapText(String text, BaseFont font, float size, float maxWidth, int maxLines) {
        String[] words = pdfSafe(text).replaceAll("\\s+", " ").trim().split(" ");
        List<String> lines = new ArrayList<>();
        String cur = "";
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = cur.isEmpty() ? word : cur + " " + word;
            if (!cur.isEmpty() && width(font, candidate, size) > maxWidth) {
                lines.add(cur);
                cur = word;
                if (lines.size() >= maxLines) {
                    break;
                }
            } else {
                cur = candidate;
            }
        }
        if (!cur.isEmpty() && lines.size() < maxLines) {
            lines.add(cur);
        }
        return lines.isEmpty() ? List.of("") : lines.subList(0, Math.min(lines.size(), maxLines));
    }

    private static String truncate(String text, BaseFont font, float size, float maxWidth) {
        String clean = pdfSafe(text).replaceAll("\\s+", " ").trim();
        if (width(font, clean, size) <= maxWidth) {
            return clean;
        }
        String s = clean;
        while (s.length() > 1 && width(font, s + "...", size) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }

    private static String formatQty(BigDecimal qty) {
        if (qty == null) {
            return "-";
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    private static String kshLabel(String currency) {
        if (currency == null || currency.isBlank() || "KES".equalsIgnoreCase(currency.trim())) {
            return "Ksh";
        }
        return pdfSafe(currency.trim());
    }

    private static String ksh(BigDecimal amount, String currencyLabel) {
        if (amount == null) {
            return "Ask";
        }
        return currencyLabel + " " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static Color rgb(int hex) {
        return new Color((hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF);
    }

    /**
     * Helvetica/WinAnsi cannot encode many grocery labels. Keep CP1252
     * punctuation the order sheet uses (middot, dashes) and drop the rest so
     * a single product name cannot 500 the download.
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
                case '\u2018', '\u2019', '\u201A', '\u2032' -> out.append('\'');
                case '\u201C', '\u201D', '\u201E', '\u2033' -> out.append('"');
                case '\u2026' -> out.append("...");
                case '\u2013' -> out.append((char) 0x96);
                case '\u2014' -> out.append((char) 0x97);
                case '\u2022' -> out.append((char) 0x95);
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

    private record Context(
            String title,
            String location,
            String listedBy,
            String dateShort,
            int items,
            String units,
            String currencyLabel,
            BigDecimal subtotal
    ) {}
}
