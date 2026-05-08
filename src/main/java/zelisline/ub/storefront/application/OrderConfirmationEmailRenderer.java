package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;

/**
 * Renders a creative, modern HTML order confirmation email with inline CSS
 * suitable for the widest range of email clients.
 */
@Component
public class OrderConfirmationEmailRenderer {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy 'at' hh:mm a").withZone(ZoneId.of("Africa/Nairobi"));

    // ── palette ──────────────────────────────────────────────────────────
    private static final String DARK_GREEN   = "#1B4332";
    private static final String FOREST_GREEN = "#2D6A4F";
    private static final String MED_GREEN    = "#40916C";
    private static final String SOFT_GREEN   = "#52B788";
    private static final String PALE_GREEN   = "#D8F3DC";
    private static final String CREAM        = "#FEFAE0";
    private static final String WARM_WHITE   = "#FFFDF7";
    private static final String MUTED_BROWN  = "#6B705C";

    /**
     * Render a polished, responsive-ish HTML order confirmation email.
     *
     * @param order        the saved order
     * @param lines        order lines already persisted (sorted by lineIndex)
     * @param branchName   pickup branch / catalog-branch name
     * @param businessName store / business display name
     * @return complete HTML document as a string
     */
    public String renderHtml(WebOrder order, List<WebOrderLine> lines,
                             String branchName, String businessName) {
        String storeName = businessName != null && !businessName.isBlank()
                ? escape(businessName)
                : "🥬 Palmart";

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Order Confirmed</title>
                </head>
                <body style="margin:0;padding:0;background-color:%s;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                %s
                </body>
                </html>
                """.formatted(CREAM, renderBody(order, lines, branchName, storeName));
    }

    // ── body ─────────────────────────────────────────────────────────────

    private String renderBody(WebOrder order, List<WebOrderLine> lines,
                              String branchName, String storeName) {
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s;padding:32px 16px 48px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">
                        %s
                        %s
                        %s
                        %s
                        %s
                        %s
                      </table>
                    </td>
                  </tr>
                </table>
                """.formatted(
                CREAM,
                renderHeader(storeName),
                renderHero(order),
                renderLinesTable(lines),
                renderTotals(order),
                renderPickup(order, branchName),
                renderFooter(storeName));
    }

    // ── header strip ─────────────────────────────────────────────────────

    private String renderHeader(String storeName) {
        return """
                <tr>
                  <td style="background:linear-gradient(135deg,%s 0%%,%s 100%%);padding:28px 32px;border-radius:14px 14px 0 0;text-align:center;">
                    <span style="font-size:28px;font-weight:700;color:#FFFFFF;letter-spacing:0.5px;">%s</span>
                  </td>
                </tr>
                """.formatted(FOREST_GREEN, MED_GREEN, storeName);
    }

    // ── hero section ─────────────────────────────────────────────────────

    private String renderHero(WebOrder order) {
        String dateStr = order.getCreatedAt() != null
                ? DATE_FMT.format(order.getCreatedAt())
                : "";

        return """
                <tr>
                  <td style="background-color:%s;padding:32px 32px 24px;text-align:center;">
                    <div style="font-size:15px;font-weight:600;color:%s;letter-spacing:1px;text-transform:uppercase;margin-bottom:10px;">
                      &#10003; Order Confirmed!
                    </div>
                    <div style="font-size:36px;font-weight:800;color:%s;line-height:1.2;margin-bottom:14px;">
                      Thank You
                    </div>
                    <table role="presentation" align="center" cellpadding="0" cellspacing="0" style="margin-bottom:10px;">
                      <tr>
                        <td style="background-color:%s;border-radius:20px;padding:6px 18px;">
                          <span style="font-size:13px;font-weight:700;color:%s;letter-spacing:0.4px;">
                            %s
                          </span>
                        </td>
                      </tr>
                    </table>
                    <div style="font-size:13px;color:%s;margin-top:4px;">
                      Placed on %s
                    </div>
                  </td>
                </tr>
                """.formatted(
                WARM_WHITE, SOFT_GREEN, DARK_GREEN,
                PALE_GREEN, FOREST_GREEN, escape(order.getId()),
                MUTED_BROWN, dateStr);
    }

    // ── lines table ──────────────────────────────────────────────────────

    private String renderLinesTable(List<WebOrderLine> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <tr>
                  <td style="background-color:#FFFFFF;padding:24px 32px 8px;">
                    <div style="font-size:14px;font-weight:700;color:%s;text-transform:uppercase;letter-spacing:0.6px;margin-bottom:14px;">
                      Order Details
                    </div>
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">
                      <tr style="border-bottom:2px solid %s;">
                        <td style="padding:10px 8px;font-size:11px;font-weight:700;color:%s;text-transform:uppercase;letter-spacing:0.5px;">Item</td>
                        <td style="padding:10px 8px;font-size:11px;font-weight:700;color:%s;text-transform:uppercase;letter-spacing:0.5px;">Qty</td>
                        <td style="padding:10px 8px;font-size:11px;font-weight:700;color:%s;text-transform:uppercase;letter-spacing:0.5px;text-align:right;">Unit Price</td>
                        <td style="padding:10px 8px;font-size:11px;font-weight:700;color:%s;text-transform:uppercase;letter-spacing:0.5px;text-align:right;">Line Total</td>
                      </tr>
                """.formatted(DARK_GREEN, PALE_GREEN,
                MUTED_BROWN, MUTED_BROWN, MUTED_BROWN, MUTED_BROWN));

        int i = 0;
        for (WebOrderLine line : lines) {
            String bg = (i % 2 == 0) ? "#FFFFFF" : "#FAFBF9";
            String nameCell = escape(line.getItemName());
            if (line.getVariantName() != null && !line.getVariantName().isBlank()) {
                nameCell = "<span style=\"font-weight:600;color:" + DARK_GREEN + ";\">"
                        + escape(line.getItemName()) + "</span>"
                        + "<br><span style=\"font-size:12px;color:" + MUTED_BROWN + ";\">"
                        + escape(line.getVariantName()) + "</span>";
            }
            sb.append("""
                      <tr style="border-bottom:1px solid %s;background-color:%s;">
                        <td style="padding:10px 8px;font-size:13px;color:%s;">%s</td>
                        <td style="padding:10px 8px;font-size:13px;color:%s;">%s</td>
                        <td style="padding:10px 8px;font-size:13px;color:%s;text-align:right;">%s</td>
                        <td style="padding:10px 8px;font-size:13px;font-weight:600;color:%s;text-align:right;">%s</td>
                      </tr>
                    """.formatted(
                    PALE_GREEN, bg,
                    DARK_GREEN, nameCell,
                    DARK_GREEN, formatQty(line.getQuantity()),
                    DARK_GREEN, formatKes(line.getUnitPrice()),
                    DARK_GREEN, formatKes(line.getLineTotal())));
            i++;
        }

        sb.append("""
                    </table>
                  </td>
                </tr>
                """);
        return sb.toString();
    }

    // ── totals ───────────────────────────────────────────────────────────

    private String renderTotals(WebOrder order) {
        BigDecimal grand = order.getGrandTotal() != null ? order.getGrandTotal() : BigDecimal.ZERO;
        String currency = order.getCurrency() != null ? order.getCurrency() : "KES";

        return """
                <tr>
                  <td style="background-color:#FFFFFF;padding:12px 32px 20px;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                      <tr>
                        <td style="padding:8px 0;font-size:14px;color:%s;text-align:right;">Total</td>
                        <td style="width:140px;padding:8px 0;font-size:18px;font-weight:800;color:%s;text-align:right;">
                          %s
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
                """.formatted(MUTED_BROWN, DARK_GREEN, formatKes(grand));
    }

    // ── pickup + status ──────────────────────────────────────────────────

    private String renderPickup(WebOrder order, String branchName) {
        String branch = branchName != null && !branchName.isBlank()
                ? escape(branchName)
                : "Store";
        String statusLabel = "Pending Payment";

        return """
                <tr>
                  <td style="background-color:%s;padding:20px 32px;border-top:2px solid %s;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                      <tr>
                        <td style="vertical-align:top;width:50%%;padding-right:12px;">
                          <div style="font-size:11px;font-weight:700;color:%s;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:4px;">
                            Pickup Location
                          </div>
                          <div style="font-size:14px;font-weight:600;color:%s;">
                            %s &#x1F4CD;
                          </div>
                        </td>
                        <td style="vertical-align:top;width:50%%;text-align:right;padding-left:12px;">
                          <div style="font-size:11px;font-weight:700;color:%s;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:4px;">
                            Status
                          </div>
                          <table role="presentation" align="right" cellpadding="0" cellspacing="0">
                            <tr>
                              <td style="background-color:%s;border-radius:14px;padding:5px 16px;">
                                <span style="font-size:12px;font-weight:700;color:%s;">%s</span>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
                """.formatted(
                WARM_WHITE, PALE_GREEN,
                MUTED_BROWN, DARK_GREEN, branch,
                MUTED_BROWN, PALE_GREEN, FOREST_GREEN, statusLabel);
    }

    // ── receipt footer ───────────────────────────────────────────────────

    private String renderFooter(String storeName) {
        return """
                <tr>
                  <td style="background-color:%s;padding:28px 32px;border-radius:0 0 14px 14px;text-align:center;border-top:2px dashed %s;">
                    <div style="font-size:13px;color:%s;line-height:1.7;margin-bottom:8px;">
                      Thank you for shopping with us!
                    </div>
                    <div style="font-size:12px;color:%s;line-height:1.7;">
                      Staff may contact you via phone or WhatsApp to confirm your order.
                    </div>
                    <div style="font-size:12px;color:%s;line-height:1.7;margin-bottom:10px;">
                      Questions? Reply to this email.
                    </div>
                    <div style="margin-top:14px;padding-top:14px;border-top:1px solid %s;">
                      <span style="font-size:11px;color:%s;">%s &nbsp;&#183;&nbsp; %s</span>
                    </div>
                  </td>
                </tr>
                """.formatted(
                WARM_WHITE, PALE_GREEN,
                DARK_GREEN,
                MUTED_BROWN,
                MUTED_BROWN,
                PALE_GREEN,
                MUTED_BROWN, escape(storeName),
                java.time.Year.now().getValue());
    }

    // ── formatting helpers ───────────────────────────────────────────────

    /**
     * Format a monetary amount as KES with comma grouping and 2 decimal places.
     * E.g. "KES 1,250.00"
     */
    static String formatKes(BigDecimal amount) {
        if (amount == null) {
            return "KES 0.00";
        }
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        nf.setGroupingUsed(true);
        return "KES " + nf.format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Format a quantity — strip trailing zeros but keep at least one decimal if needed.
     */
    static String formatQty(BigDecimal qty) {
        if (qty == null) {
            return "0";
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    /** Minimal HTML entity escaping. */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
