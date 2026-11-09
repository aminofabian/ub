package zelisline.ub.exports.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Neutral, format-agnostic tabular report model. Builders fill it from a report
 * service; the CSV / XLSX / PDF writers render it. Cells are typed so numeric
 * formats keep real numbers (XLSX sums, CSV plain values) while carrying a
 * presentation string for the PDF.
 */
public record ReportTable(
        String title,
        String businessName,
        List<String> metaLines,
        String generatedAtLabel,
        String currencyLabel,
        List<Column> columns,
        List<List<Cell>> rows,
        List<Cell> totals
) {
    public enum Align { LEFT, RIGHT }

    /** A column header, its alignment, and a relative width weight. */
    public record Column(String header, Align align, float weight) {
        public static Column left(String header, float weight) {
            return new Column(header, Align.LEFT, weight);
        }
        public static Column right(String header, float weight) {
            return new Column(header, Align.RIGHT, weight);
        }
    }

    public sealed interface Cell permits Cell.Text, Cell.Money, Cell.Num, Cell.Blank {
        String display();

        record Text(String value) implements Cell {
            public String display() { return value == null ? "" : value; }
        }
        /** Money amount plus its presentation string (e.g. "KES 1,200.00"). */
        record Money(BigDecimal amount, String display) implements Cell {
        }
        /** Non-money number (qty, count, percent) plus its presentation string. */
        record Num(BigDecimal value, String display) implements Cell {
        }
        record Blank() implements Cell {
            public String display() { return ""; }
        }
    }
}
