package zelisline.ub.exports.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zelisline.ub.exports.application.ReportTable.Align;
import zelisline.ub.exports.application.ReportTable.Cell;
import zelisline.ub.exports.application.ReportTable.Column;

/**
 * Unit tests for the shared report writers. Pure (no Spring): exercises CSV quoting,
 * plain-number formatting, the totals row and that XLSX/PDF produce real files.
 */
class ReportTableWritersTest {

    private ReportTable sample() {
        List<Column> cols = List.of(
                new Column("Category", Align.LEFT, 3f),
                new Column("Net revenue", Align.RIGHT, 1.5f),
                new Column("Margin", Align.RIGHT, 1f));
        List<List<Cell>> rows = List.of(
                List.of(
                        new Cell.Text("Dairy, Eggs & Chilled"),
                        new Cell.Money(new BigDecimal("76500.00"), "KES 76,500.00"),
                        new Cell.Num(new BigDecimal("20.9"), "20.9%")),
                List.of(
                        new Cell.Text("Cooking Oil"),
                        new Cell.Money(new BigDecimal("-2100.00"), "KES -2,100.00"),
                        new Cell.Num(new BigDecimal("-3.9"), "-3.9%")));
        List<Cell> totals = List.of(
                new Cell.Text("Total"),
                new Cell.Money(new BigDecimal("74400.00"), "KES 74,400.00"),
                new Cell.Blank());
        return new ReportTable("Sales by category", "Palmart Mini Mart",
                List.of("Oct 2026", "All branches"), "9 Nov 2026, 12:05 EAT", "KES",
                cols, rows, totals);
    }

    @Test
    void csvQuotesCommasWritesPlainNumbersAndTotals(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("report.csv");
        ReportTableWriters.writeCsv(file, sample());
        List<String> lines = Files.readAllLines(file);

        assertEquals("Category,Net revenue,Margin", lines.get(0));
        // comma inside a label must be quoted; money is scaled plain, no currency prefix
        assertEquals("\"Dairy, Eggs & Chilled\",76500.00,20.9", lines.get(1));
        assertEquals("Cooking Oil,-2100.00,-3.9", lines.get(2));
        // blank total cell leaves a trailing empty field
        assertEquals("Total,74400.00,", lines.get(3));
        assertEquals(4, lines.size());
    }

    @Test
    void xlsxAndPdfProduceRealFiles(@TempDir Path dir) throws Exception {
        Path xlsx = dir.resolve("report.xlsx");
        ReportTableWriters.writeXlsx(xlsx, sample());
        assertTrue(Files.size(xlsx) > 0, "xlsx should be non-empty");

        Path pdf = dir.resolve("report.pdf");
        ReportTableWriters.writePdf(pdf, sample());
        byte[] bytes = Files.readAllBytes(pdf);
        assertTrue(bytes.length > 0, "pdf should be non-empty");
        assertEquals('%', (char) bytes[0]);
        assertEquals('P', (char) bytes[1]);
        assertEquals('D', (char) bytes[2]);
        assertEquals('F', (char) bytes[3]);
    }
}
