package zelisline.ub.exports.application;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

import zelisline.ub.exports.application.ReportTable.Align;
import zelisline.ub.exports.application.ReportTable.Cell;
import zelisline.ub.exports.application.ReportTable.Column;

/**
 * Renders any {@link ReportTable} as a tidy A4 sheet: sage paper, a forest
 * header carrying the shop, report title and scope, a ruled table with
 * per-column alignment (the first, left column wraps; numeric columns are
 * right-aligned on one line), a bold totals rule, and a footer with the
 * generation time and page numbers.
 *
 * <p>Self-contained draw primitives and WinAnsi mapping so one odd character in
 * a category or supplier name cannot fail the whole download.</p>
 */
public final class ReportTablePdfRenderer {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;
    private static final float MARGIN = 40f;
    private static final float CONTENT_W = PAGE_W - MARGIN * 2;
    private static final float FOOTER_H = 44f;
    private static final float CELL_PAD = 7f;
    private static final float ROW_MIN = 24f;
    private static final float LINE_H = 13f;

    private static final Color INK = rgb(0x24312A);
    private static final Color INK_SOFT = rgb(0x4A5A4E);
    private static final Color PAPER = rgb(0xF4F6F2);
    private static final Color PAPER_RAISED = rgb(0xF8FAF6);
    private static final Color ROW_ALT = rgb(0xEDF1E9);
    private static final Color LINE = rgb(0xD5DCCE);
    private static final Color FOREST = rgb(0x2F5233);
    private static final Color FOREST_DEEP = rgb(0x1E3B26);
    private static final Color MANGO = rgb(0xB9691A);
    private static final Color TOMATO = rgb(0xC1452B);
    private static final Color HERO_MUTED = rgb(0xC5D4BE);

    private static final BaseFont HELVETICA = font(BaseFont.HELVETICA);
    private static final BaseFont HELVETICA_BOLD = font(BaseFont.HELVETICA_BOLD);
    private static final BaseFont COURIER_BOLD = font(BaseFont.COURIER_BOLD);
    private static final BaseFont TIMES_BOLD = font(BaseFont.TIMES_BOLD);

    private ReportTablePdfRenderer() {
    }

    public static byte[] render(ReportTable table) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 0, 0, 0, 0);
            PdfWriter writer = PdfWriter.getInstance(doc, baos);
            doc.open();

            float[] edges = columnEdges(table.columns());
            List<List<Cell>> rows = table.rows() == null ? List.of() : table.rows();
            int pageCount = countPages(table, rows, edges);

            PdfContentByte cb = writer.getDirectContent();
            int pageNo = 1;
            float cursor = paintHero(cb, table, true, edges);
            int index = 0;

            for (List<Cell> row : rows) {
                float rowH = rowHeight(table, row, edges);
                if (cursor - rowH < FOOTER_H + 56f) {
                    paintFooter(cb, table, pageNo, pageCount);
                    doc.newPage();
                    pageNo++;
                    cursor = paintHero(cb, table, false, edges);
                }
                cursor = drawRow(cb, cursor, table, row, edges, index++ % 2 == 1);
            }

            if (table.totals() != null) {
                if (cursor < FOOTER_H + 72f) {
                    paintFooter(cb, table, pageNo, pageCount);
                    doc.newPage();
                    pageNo++;
                    cursor = paintHero(cb, table, false, edges);
                }
                drawTotals(cb, cursor, table, edges);
            }
            paintFooter(cb, table, pageNo, pageCount);

            doc.close();
            return baos.toByteArray();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to render report PDF", e);
        }
    }

    /** Running x edges; edges[i]..edges[i+1] is column i. */
    private static float[] columnEdges(List<Column> columns) {
        float sum = 0f;
        for (Column c : columns) {
            sum += Math.max(c.weight(), 0.01f);
        }
        float[] edges = new float[columns.size() + 1];
        edges[0] = MARGIN;
        float x = MARGIN;
        for (int i = 0; i < columns.size(); i++) {
            x += Math.max(columns.get(i).weight(), 0.01f) / sum * CONTENT_W;
            edges[i + 1] = x;
        }
        edges[columns.size()] = PAGE_W - MARGIN;
        return edges;
    }

    private static int countPages(ReportTable table, List<List<Cell>> rows, float[] edges) {
        int pages = 1;
        float cursor = PAGE_H - heroDrop(table, true) - headsHeight();
        for (List<Cell> row : rows) {
            float rowH = rowHeight(table, row, edges);
            if (cursor - rowH < FOOTER_H + 56f) {
                pages++;
                cursor = PAGE_H - heroDrop(table, false) - headsHeight();
            }
            cursor -= rowH;
        }
        if (table.totals() != null && cursor < FOOTER_H + 72f) {
            pages++;
        }
        return pages;
    }

    private static float headsHeight() {
        return 18f;
    }

    private static float heroDrop(ReportTable table, boolean first) {
        if (!first) {
            return 40f;
        }
        int metas = table.metaLines() == null ? 0 : table.metaLines().size();
        return 54f + metas * 12f + 10f;
    }

    private static float paintHero(PdfContentByte cb, ReportTable table, boolean first, float[] edges) {
        cb.setColorFill(PAPER);
        cb.rectangle(0, 0, PAGE_W, PAGE_H);
        cb.fill();

        float drop = heroDrop(table, first);
        cb.setColorFill(FOREST_DEEP);
        cb.rectangle(0, PAGE_H - drop, PAGE_W, drop);
        cb.fill();
        cb.setColorFill(FOREST);
        cb.rectangle(0, PAGE_H - drop, PAGE_W, 3f);
        cb.fill();

        if (!first) {
            text(cb, MARGIN, PAGE_H - 26f,
                    truncate(nvl(table.title()), TIMES_BOLD, 12, CONTENT_W - 90f), TIMES_BOLD, 12, PAPER_RAISED);
            textRight(cb, PAGE_W - MARGIN, PAGE_H - 26f, "Continued", HELVETICA, 8, HERO_MUTED);
            return paintHeads(cb, table, PAGE_H - drop - 14f, edges);
        }

        float y = PAGE_H - 28f;
        text(cb, MARGIN, y, truncate(nvl(table.businessName()), TIMES_BOLD, 17, CONTENT_W), TIMES_BOLD, 17, PAPER_RAISED);
        y -= 17f;
        text(cb, MARGIN, y, nvl(table.title()).toUpperCase(), HELVETICA_BOLD, 9.5f, HERO_MUTED);
        y -= 15f;
        if (table.metaLines() != null) {
            for (String meta : table.metaLines()) {
                text(cb, MARGIN, y, truncate(nvl(meta), HELVETICA, 8.5f, CONTENT_W), HELVETICA, 8.5f, HERO_MUTED);
                y -= 12f;
            }
        }
        return paintHeads(cb, table, PAGE_H - drop - 14f, edges);
    }

    private static float paintHeads(PdfContentByte cb, ReportTable table, float y, float[] edges) {
        List<Column> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            Column col = columns.get(i);
            String head = col.header() == null ? "" : col.header().toUpperCase();
            if (col.align() == Align.RIGHT) {
                textRight(cb, edges[i + 1] - CELL_PAD, y, head, HELVETICA_BOLD, 7, INK_SOFT);
            } else {
                text(cb, edges[i] + (i == 0 ? 0 : CELL_PAD), y, head, HELVETICA_BOLD, 7, INK_SOFT);
            }
        }
        stroke(cb, MARGIN, y - 6f, PAGE_W - MARGIN, y - 6f, FOREST, 0.8f);
        return y - 12f;
    }

    private static float rowHeight(ReportTable table, List<Cell> row, float[] edges) {
        float labelW = edges[1] - edges[0] - CELL_PAD;
        List<String> wrapped = wrapText(pdfSafe(cellText(row, 0)), HELVETICA, 10, labelW, 2);
        return Math.max(8f + wrapped.size() * LINE_H + 8f, ROW_MIN);
    }

    private static float drawRow(
            PdfContentByte cb, float cursorY, ReportTable table, List<Cell> row, float[] edges, boolean alt) {
        float h = rowHeight(table, row, edges);
        float y = cursorY - h;
        if (alt) {
            cb.setColorFill(ROW_ALT);
            cb.rectangle(MARGIN, y, CONTENT_W, h);
            cb.fill();
        }
        List<Column> columns = table.columns();
        float firstLineY = cursorY - 15f;
        for (int i = 0; i < columns.size() && i < row.size(); i++) {
            Column col = columns.get(i);
            Cell cell = row.get(i);
            Color color = cellColor(cell, false);
            if (i == 0) {
                float labelW = edges[1] - edges[0] - CELL_PAD;
                List<String> wrapped = wrapText(pdfSafe(cell.display()), HELVETICA, 10, labelW, 2);
                float ty = firstLineY;
                for (String line : wrapped) {
                    text(cb, edges[0], ty, line, HELVETICA, 10, INK);
                    ty -= LINE_H;
                }
            } else if (col.align() == Align.RIGHT) {
                textRight(cb, edges[i + 1] - CELL_PAD, firstLineY, pdfSafe(cell.display()),
                        numericFont(cell), 9.5f, color);
            } else {
                text(cb, edges[i] + CELL_PAD, firstLineY, truncate(pdfSafe(cell.display()), HELVETICA, 10,
                        edges[i + 1] - edges[i] - CELL_PAD * 2), HELVETICA, 10, color);
            }
        }
        stroke(cb, MARGIN, y, PAGE_W - MARGIN, y, LINE, 0.35f);
        return y;
    }

    private static void drawTotals(PdfContentByte cb, float cursorY, ReportTable table, float[] edges) {
        float y = cursorY - 20f;
        stroke(cb, MARGIN, y + 15f, PAGE_W - MARGIN, y + 15f, FOREST, 0.9f);
        List<Column> columns = table.columns();
        List<Cell> totals = table.totals();
        for (int i = 0; i < columns.size() && i < totals.size(); i++) {
            Column col = columns.get(i);
            Cell cell = totals.get(i);
            if (i == 0) {
                text(cb, edges[0], y, pdfSafe(cell.display().isEmpty() ? "Total" : cell.display()),
                        HELVETICA_BOLD, 11, INK);
            } else if (col.align() == Align.RIGHT) {
                boolean money = cell instanceof Cell.Money;
                textRight(cb, edges[i + 1] - CELL_PAD, y, pdfSafe(cell.display()),
                        money ? COURIER_BOLD : HELVETICA_BOLD, money ? 11 : 10,
                        cellColor(cell, true));
            } else {
                text(cb, edges[i] + CELL_PAD, y, pdfSafe(cell.display()), HELVETICA_BOLD, 10, INK);
            }
        }
    }

    private static void paintFooter(PdfContentByte cb, ReportTable table, int pageNo, int of) {
        stroke(cb, MARGIN, 30f, PAGE_W - MARGIN, 30f, LINE, 0.35f);
        String left = "Kiosk.ke";
        if (table.generatedAtLabel() != null && !table.generatedAtLabel().isBlank()) {
            left += "  ·  Generated " + table.generatedAtLabel();
        }
        text(cb, MARGIN, 18f, pdfSafe(left), HELVETICA, 8, INK_SOFT);
        textRight(cb, PAGE_W - MARGIN, 18f, pdfSafe(pageNo + " / " + of), HELVETICA, 8, INK_SOFT);
    }

    // ---- cell helpers ----------------------------------------------------

    private static String cellText(List<Cell> row, int i) {
        return i < row.size() ? row.get(i).display() : "";
    }

    private static Color cellColor(Cell cell, boolean total) {
        if (cell instanceof Cell.Money m) {
            if (m.amount() != null && m.amount().signum() < 0) {
                return TOMATO;
            }
            return total ? MANGO : INK;
        }
        if (cell instanceof Cell.Num n) {
            if (n.value() != null && n.value().signum() < 0) {
                return TOMATO;
            }
            return INK_SOFT;
        }
        return INK;
    }

    private static BaseFont numericFont(Cell cell) {
        return cell instanceof Cell.Money ? COURIER_BOLD : HELVETICA;
    }

    // ---- draw primitives -------------------------------------------------

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
            PdfContentByte cb, float xRight, float y, String value, BaseFont font, float size, Color color) {
        String safe = pdfSafe(value);
        text(cb, xRight - width(font, safe, size), y, safe, font, size, color);
    }

    private static void stroke(PdfContentByte cb, float x1, float y1, float x2, float y2, Color color, float w) {
        cb.setLineWidth(w);
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

    private static BaseFont font(String name) {
        try {
            return BaseFont.createFont(name, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Color rgb(int hex) {
        return new Color((hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF);
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    /** Map to WinAnsi-encodable text so one odd glyph cannot fail the PDF. */
    static String pdfSafe(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case ' ', ' ', ' ' -> out.append(' ');
                case '‘', '’', '‚', '′' -> out.append('\'');
                case '“', '”', '„', '″' -> out.append('"');
                case '…' -> out.append("...");
                case '–' -> out.append((char) 0x96);
                case '—' -> out.append((char) 0x97);
                case '•', '·' -> out.append((char) 0x95);
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
