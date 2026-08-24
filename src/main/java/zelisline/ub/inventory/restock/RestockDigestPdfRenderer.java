package zelisline.ub.inventory.restock;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Restock closing sheet: sage paper, slim forest header, a numbered table.
 * Built-in Type1 fonts are WinAnsi only — grocery names are mapped before draw.
 */
public final class RestockDigestPdfRenderer {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;
    private static final float MARGIN = 36f;
    private static final float FOOTER_H = 44f;
    private static final float HEADS_H = 22f;
    private static final float CONTINUED_H = 48f;

    private static final float COL_AMOUNT = 86f;
    private static final float COL_QTY = 50f;
    private static final float COL_PAR = 50f;
    private static final float COL_ON_HAND = 58f;
    private static final float COL_GAP = 10f;

    private static final float AMOUNT_RIGHT = PAGE_W - MARGIN;
    private static final float QTY_RIGHT = AMOUNT_RIGHT - COL_AMOUNT;
    private static final float PAR_RIGHT = QTY_RIGHT - COL_QTY;
    private static final float ON_HAND_RIGHT = PAR_RIGHT - COL_PAR;
    private static final float NAME_MAX = ON_HAND_RIGHT - COL_ON_HAND - COL_GAP - MARGIN;

    private static final Color INK = rgb(0x24312A);
    private static final Color INK_SOFT = rgb(0x4A5A4E);
    private static final Color PAPER = rgb(0xF4F6F2);
    private static final Color PAPER_RAISED = rgb(0xF8FAF6);
    private static final Color LINE = rgb(0xD5DCCE);
    private static final Color FOREST = rgb(0x2F5233);
    private static final Color FOREST_DEEP = rgb(0x1E3B26);
    private static final Color MANGO = rgb(0xB9691A);
    private static final Color TOMATO = rgb(0xC1452B);
    private static final Color HERO_MUTED = rgb(0xC5D4BE);
    private static final Color WHITE = Color.WHITE;

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPORT_SKU_RE = Pattern.compile("^IMP-", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARCODE_MIRROR_SKU_RE =
            Pattern.compile("^BC-\\d{8,}$", Pattern.CASE_INSENSITIVE);

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
                float rowH = rowHeight(line);
                if (cursor - rowH < FOOTER_H + 52f) {
                    paintFooter(cb, ctx, pageNo, pageCount);
                    doc.newPage();
                    pageNo++;
                    cursor = paintHero(cb, ctx, false);
                }
                cursor = drawRow(cb, cursor, line, ctx.currencyLabel);
            }

            if (cursor < FOOTER_H + 64f) {
                paintFooter(cb, ctx, pageNo, pageCount);
                doc.newPage();
                pageNo++;
                cursor = paintHero(cb, ctx, false);
            }
            drawTotal(cb, cursor, ctx);
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
        float cursor = PAGE_H - heroDrop(ctx, true) - HEADS_H - 6f;
        for (RestockDigestPdfLine line : lines) {
            float rowH = rowHeight(line);
            if (cursor - rowH < FOOTER_H + 52f) {
                pages++;
                cursor = PAGE_H - CONTINUED_H - HEADS_H - 6f;
            }
            cursor -= rowH;
        }
        if (cursor < FOOTER_H + 64f) {
            pages++;
        }
        return pages;
    }

    private static float heroDrop(Context ctx, boolean first) {
        if (!first) {
            return CONTINUED_H;
        }
        List<String> nameLines = wrapText(ctx.title, TIMES_BOLD, 17, PAGE_W - MARGIN * 2, 2);
        return 22f + nameLines.size() * 20f + 18f;
    }

    private static float paintHero(PdfContentByte cb, Context ctx, boolean first) {
        cb.setColorFill(PAPER);
        cb.rectangle(0, 0, PAGE_W, PAGE_H);
        cb.fill();

        if (!first) {
            cb.setColorFill(FOREST_DEEP);
            cb.rectangle(0, PAGE_H - CONTINUED_H, PAGE_W, CONTINUED_H);
            cb.fill();
            text(cb, MARGIN, PAGE_H - 30f, truncate(ctx.title, TIMES_BOLD, 12, PAGE_W - MARGIN * 2 - 90f),
                    TIMES_BOLD, 12, WHITE);
            textRight(cb, PAGE_W - MARGIN, PAGE_H - 30f, "Continued", HELVETICA, 8, HERO_MUTED);
            return paintColumnHeads(cb, PAGE_H - CONTINUED_H - 16f);
        }

        List<String> nameLines = wrapText(ctx.title, TIMES_BOLD, 17, PAGE_W - MARGIN * 2, 2);
        float drop = heroDrop(ctx, true);

        cb.setColorFill(FOREST_DEEP);
        cb.rectangle(0, PAGE_H - drop, PAGE_W, drop);
        cb.fill();
        cb.setColorFill(FOREST);
        cb.rectangle(0, PAGE_H - drop, PAGE_W, 3f);
        cb.fill();

        float y = PAGE_H - 26f;
        for (String line : nameLines) {
            text(cb, MARGIN, y, line, TIMES_BOLD, 17, PAPER_RAISED);
            y -= 20f;
        }
        text(cb, MARGIN, y, metaLine(ctx), HELVETICA, 8.5f, HERO_MUTED);

        return paintColumnHeads(cb, PAGE_H - drop - 16f);
    }

    private static String metaLine(Context ctx) {
        List<String> parts = new ArrayList<>();
        if (!ctx.location.isBlank()) {
            parts.add(ctx.location);
        }
        if (!ctx.dateShort.isBlank()) {
            parts.add(ctx.dateShort);
        }
        parts.add(ctx.items + (ctx.items == 1 ? " item" : " items"));
        return String.join("  ·  ", parts);
    }

    private static float paintColumnHeads(PdfContentByte cb, float y) {
        text(cb, MARGIN, y, "PRODUCT", HELVETICA_BOLD, 7, INK_SOFT);
        textRight(cb, ON_HAND_RIGHT, y, "ON HAND", HELVETICA_BOLD, 7, INK_SOFT);
        textRight(cb, PAR_RIGHT, y, "PAR", HELVETICA_BOLD, 7, INK_SOFT);
        textRight(cb, QTY_RIGHT, y, "QTY", HELVETICA_BOLD, 7, INK_SOFT);
        textRight(cb, AMOUNT_RIGHT, y, "AMOUNT", HELVETICA_BOLD, 7, INK_SOFT);
        stroke(cb, MARGIN, y - 6f, PAGE_W - MARGIN, y - 6f, FOREST, 0.8f);
        return y - 10f;
    }

    private static float rowHeight(RestockDigestPdfLine line) {
        List<String> names = wrapText(pdfSafe(nvl(line.itemName())), HELVETICA, 10, NAME_MAX, 2);
        float h = 8f + names.size() * 13f + 8f;
        if (!humanSku(line.itemSku()).isEmpty()) {
            h += 11f;
        }
        return Math.max(h, 28f);
    }

    private static float drawRow(PdfContentByte cb, float cursorY, RestockDigestPdfLine line, String currencyLabel) {
        float h = rowHeight(line);
        float y = cursorY - h;
        List<String> names = wrapText(pdfSafe(nvl(line.itemName())), HELVETICA, 10, NAME_MAX, 2);
        String sku = humanSku(line.itemSku());
        float textY = cursorY - 16f;
        for (int i = 0; i < names.size(); i++) {
            text(cb, MARGIN, textY, names.get(i), HELVETICA, 10, INK);
            textY -= 13f;
        }
        if (!sku.isEmpty()) {
            text(cb, MARGIN, textY, sku, HELVETICA, 8, INK_SOFT);
        }

        float numY = cursorY - 16f;
        textRight(cb, ON_HAND_RIGHT, numY, formatQty(line.onHand()), HELVETICA, 10, INK_SOFT);
        textRight(cb, PAR_RIGHT, numY, formatQty(line.par()), HELVETICA, 10, INK_SOFT);
        textRight(cb, QTY_RIGHT, numY, formatQty(line.quantity()), HELVETICA_BOLD, 11, INK);
        boolean ask = line.lineTotal() == null;
        String price = ask ? "Ask" : ksh(line.lineTotal(), currencyLabel);
        textRight(cb, AMOUNT_RIGHT, numY, price, COURIER_BOLD, 9.5f, ask ? TOMATO : INK);
        stroke(cb, MARGIN, y, PAGE_W - MARGIN, y, LINE, 0.35f);
        return y;
    }

    private static void drawTotal(PdfContentByte cb, float cursorY, Context ctx) {
        float y = cursorY - 18f;
        stroke(cb, MARGIN, y + 14f, PAGE_W - MARGIN, y + 14f, FOREST, 0.8f);
        text(cb, MARGIN, y, "Total", HELVETICA_BOLD, 10, INK);
        boolean priced = ctx.subtotal != null;
        textRight(
                cb,
                AMOUNT_RIGHT,
                y,
                priced ? ksh(ctx.subtotal, ctx.currencyLabel) : "Ask",
                COURIER_BOLD,
                12,
                priced ? MANGO : TOMATO);
        String summary = ctx.items + (ctx.items == 1 ? " item" : " items")
                + "  ·  "
                + ctx.units
                + ("1".equals(ctx.units) ? " unit" : " units");
        text(cb, MARGIN, y - 14f, pdfSafe(summary), HELVETICA, 8, INK_SOFT);
    }

    private static void paintFooter(PdfContentByte cb, Context ctx, int pageNo, int of) {
        stroke(cb, MARGIN, 30f, PAGE_W - MARGIN, 30f, LINE, 0.35f);
        text(cb, MARGIN, 18f, "Kiosk.ke  ·  Confirm availability and pricing.", HELVETICA, 8, INK_SOFT);
        String right = pageNo + " / " + of;
        textRight(cb, PAGE_W - MARGIN, 18f, pdfSafe(right), HELVETICA, 8, INK_SOFT);
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
                lines.add(truncate(cur, font, size, maxWidth));
                cur = word;
                if (lines.size() >= maxLines) {
                    cur = "";
                    break;
                }
            } else {
                cur = candidate;
            }
        }
        if (!cur.isEmpty() && lines.size() < maxLines) {
            lines.add(truncate(cur, font, size, maxWidth));
        }
        return lines.isEmpty() ? List.of("") : lines;
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
            return "—";
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
        return currencyLabel + " " + moneyFormat().format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    private static DecimalFormat moneyFormat() {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }

    private static String humanSku(String sku) {
        if (sku == null) {
            return "";
        }
        String t = sku.trim();
        if (t.isEmpty()
                || IMPORT_SKU_RE.matcher(t).find()
                || BARCODE_MIRROR_SKU_RE.matcher(t).matches()
                || UUID_RE.matcher(t).matches()) {
            return "";
        }
        return pdfSafe(t);
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
            String dateShort,
            int items,
            String units,
            String currencyLabel,
            BigDecimal subtotal
    ) {}
}
