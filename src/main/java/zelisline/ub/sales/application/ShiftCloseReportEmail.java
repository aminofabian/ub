package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * The note a shop gets when a shift closes, plus the row types the attached
 * PDFs are built from. Plain text and a short HTML letter; the sheets carry
 * the rows.
 */
public final class ShiftCloseReportEmail {

    static final int STOCK_CAP = 500;
    static final int SALES_CAP = 150;

    private static final Locale KE = Locale.forLanguageTag("en-KE");

    private ShiftCloseReportEmail() {
    }

    public record StockRow(
            String name, BigDecimal stock, BigDecimal buyPrice, BigDecimal sellPrice, String category) {

        public StockRow(String name, BigDecimal stock, BigDecimal buyPrice, BigDecimal sellPrice) {
            this(name, stock, buyPrice, sellPrice, null);
        }
    }

    public record SaleRow(String name, BigDecimal quantity, BigDecimal amount) {
    }

    public record Tender(String method, BigDecimal amount) {
    }

    public record Letter(String subject, String text, String html) {
    }

    /** Short cover. The stock sheet and the day's receipts ride along as PDFs. */
    public static Letter cover(
            String shop,
            String branch,
            String day,
            int saleCount,
            BigDecimal total,
            List<Tender> tenders
    ) {
        String subject = shop + " shift closed";
        String count = saleCount == 0
                ? "Nothing completed yet today."
                : saleCount + (saleCount == 1 ? " sale, " : " sales, ") + money(total) + ".";
        String paid = tenderLine(tenders);
        StringBuilder text = new StringBuilder();
        text.append(shop).append(" closed a shift at ").append(branch).append(" on ").append(day).append(".\n");
        text.append(count).append('\n');
        if (!paid.isEmpty()) {
            text.append(paid).append('\n');
        }
        text.append("\nTwo reports are attached: stock with buying and selling prices, ")
                .append("and today's sales, receipt by receipt.\n");
        return new Letter(subject, finish(text), coverHtml(shop, branch, day, count, paid));
    }

    public static Letter stock(
            String shop,
            String branch,
            String when,
            List<StockRow> rows,
            int omitted
    ) {
        String title = shop + " stock at shift close";
        String intro = shop + " · " + branch + " · " + when
                + ". On-hand quantity with buying and selling prices.";
        StringBuilder text = new StringBuilder(intro).append("\n\n");
        StringBuilder table = new StringBuilder();
        table.append(head("Item", "Stock", "Buying", "Selling"));
        for (StockRow row : rows) {
            text.append(row.name())
                    .append("  ")
                    .append(qty(row.stock()))
                    .append("  buy ")
                    .append(money(row.buyPrice()))
                    .append("  sell ")
                    .append(money(row.sellPrice()))
                    .append('\n');
            table.append(cells(row.name(), qty(row.stock()), money(row.buyPrice()), money(row.sellPrice())));
        }
        if (rows.isEmpty()) {
            text.append("No stocked items at this branch.\n");
        }
        appendOmitted(text, omitted);
        return new Letter(title, finish(text), document(title, intro, table.toString(), omitted));
    }

    public static Letter sales(
            String shop,
            String branch,
            String day,
            int saleCount,
            BigDecimal total,
            List<Tender> tenders,
            List<SaleRow> rows,
            int omitted
    ) {
        String title = shop + " sales today";
        String intro = shop + " · " + branch + " · " + day + ". "
                + saleCount + (saleCount == 1 ? " sale" : " sales")
                + ", " + money(total) + ".";
        StringBuilder text = new StringBuilder(intro).append("\n");
        StringBuilder table = new StringBuilder();
        for (Tender tender : tenders) {
            text.append(tenderLabel(tender.method())).append("  ").append(money(tender.amount())).append('\n');
        }
        text.append('\n');
        table.append(head("Item", "Qty", "Amount", ""));
        for (SaleRow row : rows) {
            text.append(row.name()).append("  ").append(qty(row.quantity()))
                    .append("  ").append(money(row.amount())).append('\n');
            table.append(cells(row.name(), qty(row.quantity()), money(row.amount()), ""));
        }
        if (rows.isEmpty()) {
            text.append("Nothing completed yet today.\n");
        }
        appendOmitted(text, omitted);
        return new Letter(title, finish(text), document(title, intro, table.toString(), omitted));
    }

    private static void appendOmitted(StringBuilder text, int omitted) {
        if (omitted > 0) {
            text.append("\nShowing the first rows. The rest is not in this email.\n");
        }
    }

    private static String finish(StringBuilder text) {
        text.append("\nTurn these emails off in Business settings, under Shifts & cash drawer.\n");
        return text.toString();
    }

    private static String document(String title, String intro, String rows, int omitted) {
        String more = omitted > 0
                ? "<p style=\"color:#555\">Showing the first rows. The rest is not in this email.</p>"
                : "";
        return "<!DOCTYPE html><html><body style=\"font-family:sans-serif;color:#15231f\">"
                + "<h1 style=\"font-size:18px\">" + esc(title) + "</h1>"
                + "<p>" + esc(intro) + "</p>"
                + "<table style=\"border-collapse:collapse;width:100%\">" + rows + "</table>"
                + more
                + "<p style=\"color:#666;font-size:12px\">Turn these emails off in Business settings, "
                + "under Shifts &amp; cash drawer.</p>"
                + "</body></html>";
    }

    private static String head(String a, String b, String c, String d) {
        return row(true, a, b, c, d);
    }

    private static String cells(String a, String b, String c, String d) {
        return row(false, a, b, c, d);
    }

    private static String row(boolean header, String a, String b, String c, String d) {
        String tag = header ? "th" : "td";
        String align = header
                ? "text-align:left;font-size:11px;color:#666;padding:4px 8px"
                : "text-align:left;padding:4px 8px;border-top:1px solid #e4e6e3";
        String right = align.replace("left", "right");
        return "<tr><" + tag + " style=\"" + align + "\">" + esc(a) + "</" + tag + ">"
                + "<" + tag + " style=\"" + right + "\">" + esc(b) + "</" + tag + ">"
                + "<" + tag + " style=\"" + right + "\">" + esc(c) + "</" + tag + ">"
                + (d.isEmpty() ? "" : "<" + tag + " style=\"" + right + "\">" + esc(d) + "</" + tag + ">")
                + "</tr>";
    }

    static String money(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        NumberFormat format = NumberFormat.getNumberInstance(KE);
        format.setMaximumFractionDigits(2);
        format.setMinimumFractionDigits(rounded.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return format.format(rounded);
    }

    static String qty(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        BigDecimal rounded = value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        NumberFormat format = NumberFormat.getNumberInstance(KE);
        format.setMaximumFractionDigits(3);
        return format.format(rounded);
    }

    private static String tenderLine(List<Tender> tenders) {
        StringBuilder out = new StringBuilder();
        if (tenders == null) {
            return "";
        }
        for (Tender tender : tenders) {
            if (out.length() > 0) {
                out.append("  ·  ");
            }
            out.append(tenderLabel(tender.method())).append(' ').append(money(tender.amount()));
        }
        return out.toString();
    }

    private static String coverHtml(String shop, String branch, String day, String count, String paid) {
        String paidHtml = paid.isEmpty() ? "" : "<p style=\"font-family:sans-serif;margin:8px 0 0\">" + esc(paid) + "</p>";
        return "<!DOCTYPE html><html><body style=\"background:#F4F1EA;color:#121A16;margin:0;padding:32px\">"
                + "<p style=\"font-family:Georgia,serif;font-size:22px;margin:0\">" + esc(shop) + "</p>"
                + "<p style=\"font-family:Georgia,serif;font-style:italic;color:#1D4B3B;margin:6px 0 0\">Shift closed</p>"
                + "<p style=\"font-family:sans-serif;color:#556159;font-size:13px;margin:14px 0 0\">"
                + esc(branch) + " · " + esc(day) + "</p>"
                + "<p style=\"font-family:sans-serif;margin:8px 0 0\">" + esc(count) + "</p>"
                + paidHtml
                + "<p style=\"font-family:sans-serif;margin:16px 0 0\">Two reports are attached: "
                + "stock with buying and selling prices, and today's sales, receipt by receipt.</p>"
                + "<p style=\"font-family:sans-serif;color:#556159;font-size:12px;margin:24px 0 0\">"
                + "Turn these emails off in Business settings, under Shifts &amp; cash drawer.</p>"
                + "</body></html>";
    }

    static String tenderLabel(String method) {
        if (method == null || method.isBlank()) {
            return "Other";
        }
        String lower = method.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
