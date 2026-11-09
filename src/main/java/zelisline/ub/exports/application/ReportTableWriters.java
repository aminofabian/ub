package zelisline.ub.exports.application;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Renders a {@link ReportTable} to CSV, XLSX or PDF. Kept free of Spring and of
 * any one report so it is unit-testable in isolation and shared by every report.
 */
public final class ReportTableWriters {

    private ReportTableWriters() {
    }

    // ---- CSV -------------------------------------------------------------

    public static void writeCsv(Path file, ReportTable table) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeCsvRow(w, headerCells(table));
            for (var row : table.rows()) {
                writeCsvRow(w, rawRow(row));
            }
            if (table.totals() != null) {
                writeCsvRow(w, rawRow(table.totals()));
            }
        }
    }

    private static String[] headerCells(ReportTable table) {
        return table.columns().stream().map(ReportTable.Column::header).toArray(String[]::new);
    }

    private static String[] rawRow(java.util.List<ReportTable.Cell> cells) {
        String[] out = new String[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            out[i] = rawValue(cells.get(i));
        }
        return out;
    }

    /** Machine-friendly value for CSV: plain numbers, raw text. */
    private static String rawValue(ReportTable.Cell cell) {
        return switch (cell) {
            case ReportTable.Cell.Money m -> m.amount() == null
                    ? "" : m.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
            case ReportTable.Cell.Num n -> n.value() == null ? "" : n.value().toPlainString();
            case ReportTable.Cell.Text t -> t.value() == null ? "" : t.value();
            case ReportTable.Cell.Blank b -> "";
        };
    }

    private static void writeCsvRow(BufferedWriter w, String[] cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                w.write(',');
            }
            w.write(csvCell(cells[i]));
        }
        w.write('\n');
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ---- XLSX ------------------------------------------------------------

    public static void writeXlsx(Path file, ReportTable table) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet(sheetName(table.title()));

            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 13);
            CellStyle titleStyle = wb.createCellStyle();
            titleStyle.setFont(titleFont);

            Font headFont = wb.createFont();
            headFont.setBold(true);
            CellStyle headStyle = wb.createCellStyle();
            headStyle.setFont(headFont);

            CellStyle moneyStyle = wb.createCellStyle();
            moneyStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle totalMoneyStyle = wb.createCellStyle();
            totalMoneyStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            totalMoneyStyle.setFont(headFont);

            int r = 0;
            Row titleRow = sh.createRow(r++);
            Cell tc = titleRow.createCell(0);
            tc.setCellValue(nvl(table.businessName()));
            tc.setCellStyle(titleStyle);
            sh.createRow(r++).createCell(0).setCellValue(nvl(table.title()));
            if (table.metaLines() != null) {
                for (String meta : table.metaLines()) {
                    sh.createRow(r++).createCell(0).setCellValue(nvl(meta));
                }
            }
            sh.createRow(r++).createCell(0).setCellValue(
                    "Amounts in " + nvl(table.currencyLabel())
                            + "  |  Generated " + nvl(table.generatedAtLabel()));
            r++; // spacer

            Row header = sh.createRow(r++);
            for (int c = 0; c < table.columns().size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(table.columns().get(c).header());
                cell.setCellStyle(headStyle);
            }

            for (var row : table.rows()) {
                Row body = sh.createRow(r++);
                writeXlsxCells(body, row, moneyStyle, null);
            }
            if (table.totals() != null) {
                Row totals = sh.createRow(r++);
                writeXlsxCells(totals, table.totals(), totalMoneyStyle, headStyle);
            }

            for (int c = 0; c < table.columns().size(); c++) {
                sh.setColumnWidth(c, c == 0 ? 11000 : 4200);
            }

            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
    }

    private static void writeXlsxCells(
            Row row, java.util.List<ReportTable.Cell> cells, CellStyle moneyStyle, CellStyle textStyle) {
        for (int c = 0; c < cells.size(); c++) {
            Cell cell = row.createCell(c);
            ReportTable.Cell model = cells.get(c);
            switch (model) {
                case ReportTable.Cell.Money m -> {
                    if (m.amount() != null) {
                        cell.setCellValue(m.amount().setScale(2, RoundingMode.HALF_UP).doubleValue());
                        cell.setCellStyle(moneyStyle);
                    }
                }
                case ReportTable.Cell.Num n -> {
                    if (n.value() != null) {
                        cell.setCellValue(n.value().doubleValue());
                    }
                }
                case ReportTable.Cell.Text t -> {
                    cell.setCellValue(t.value() == null ? "" : t.value());
                    if (textStyle != null) {
                        cell.setCellStyle(textStyle);
                    }
                }
                case ReportTable.Cell.Blank b -> { }
            }
        }
    }

    private static String sheetName(String title) {
        String base = (title == null || title.isBlank()) ? "Report" : title;
        base = base.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return base.length() > 31 ? base.substring(0, 31) : base;
    }

    // ---- PDF -------------------------------------------------------------

    public static void writePdf(Path file, ReportTable table) throws IOException {
        byte[] bytes = ReportTablePdfRenderer.render(table);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }
}
