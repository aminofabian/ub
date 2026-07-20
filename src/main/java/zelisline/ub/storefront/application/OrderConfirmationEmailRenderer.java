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
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;

/**
 * Renders a creative, modern HTML order confirmation email with inline CSS
 * suitable for the widest range of email clients. Colours and store name come
 * from tenant branding when set.
 */
@Component
public class OrderConfirmationEmailRenderer {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy 'at' hh:mm a").withZone(ZoneId.of("Africa/Nairobi"));

    // ── default palette (used when tenant colours are unset) ─────────────
    private static final String DEFAULT_PRIMARY = "#2D6A4F";
    private static final String DEFAULT_ACCENT  = "#40916C";
    private static final String CREAM           = "#FEFAE0";
    private static final String WARM_WHITE      = "#FFFDF7";
    private static final String MUTED_BROWN     = "#6B705C";

    /**
     * @param order     the saved order
     * @param lines     order lines already persisted (sorted by lineIndex)
     * @param branchName pickup branch / catalog-branch name
     * @param branding  tenant branding (display name + colours); may be null
     * @param fallbackBusinessName business name when branding display name is blank
     * @param businessSlug tenant slug used when name is a platform placeholder
     */
    public String renderHtml(
            WebOrder order,
            List<WebOrderLine> lines,
            String branchName,
            TenantBrandingDto branding,
            String fallbackBusinessName,
            String businessSlug) {
        String storeName = resolveStoreName(branding, fallbackBusinessName, businessSlug);
        Palette palette = Palette.from(branding);

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
                """.formatted(CREAM, renderBody(order, lines, branchName, storeName, palette));
    }

    public String renderHtml(
            WebOrder order,
            List<WebOrderLine> lines,
            String branchName,
            TenantBrandingDto branding,
            String fallbackBusinessName) {
        return renderHtml(order, lines, branchName, branding, fallbackBusinessName, null);
    }

    /** Prefer {@link #renderHtml(WebOrder, List, String, TenantBrandingDto, String, String)}. */
    @Deprecated
    public String renderHtml(WebOrder order, List<WebOrderLine> lines,
                             String branchName, String businessName) {
        return renderHtml(order, lines, branchName, null, businessName, null);
    }

    // ── body ─────────────────────────────────────────────────────────────

    private String renderBody(
            WebOrder order,
            List<WebOrderLine> lines,
            String branchName,
            String storeName,
            Palette palette) {
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
                renderHeader(storeName, palette),
                renderHero(order, palette),
                renderLinesTable(lines, palette),
                renderTotals(order, palette),
                renderPickup(order, branchName, palette),
                renderFooter(storeName, palette));
    }

    // ── header strip ─────────────────────────────────────────────────────

    private String renderHeader(String storeName, Palette palette) {
        return """
                <tr>
                  <td style="background:linear-gradient(135deg,%s 0%%,%s 100%%);padding:28px 32px;border-radius:14px 14px 0 0;text-align:center;">
                    <span style="font-size:28px;font-weight:700;color:#FFFFFF;letter-spacing:0.5px;">%s</span>
                  </td>
                </tr>
                """.formatted(palette.primary, palette.accent, escape(storeName));
    }

    // ── hero section ─────────────────────────────────────────────────────

    private String renderHero(WebOrder order, Palette palette) {
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
                WARM_WHITE, palette.soft, palette.dark,
                palette.pale, palette.primary, escape(order.getId()),
                MUTED_BROWN, dateStr);
    }

    // ── lines table ──────────────────────────────────────────────────────

    private String renderLinesTable(List<WebOrderLine> lines, Palette palette) {
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
                """.formatted(palette.dark, palette.pale,
                MUTED_BROWN, MUTED_BROWN, MUTED_BROWN, MUTED_BROWN));

        int i = 0;
        for (WebOrderLine line : lines) {
            String bg = (i % 2 == 0) ? "#FFFFFF" : "#FAFBF9";
            String nameCell = escape(line.getItemName());
            if (line.getVariantName() != null && !line.getVariantName().isBlank()) {
                nameCell = "<span style=\"font-weight:600;color:" + palette.dark + ";\">"
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
                    palette.pale, bg,
                    palette.dark, nameCell,
                    palette.dark, formatQty(line.getQuantity()),
                    palette.dark, formatKes(line.getUnitPrice()),
                    palette.dark, formatKes(line.getLineTotal())));
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

    private String renderTotals(WebOrder order, Palette palette) {
        BigDecimal grand = order.getGrandTotal() != null ? order.getGrandTotal() : BigDecimal.ZERO;

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
                """.formatted(MUTED_BROWN, palette.dark, formatKes(grand));
    }

    // ── pickup + status ──────────────────────────────────────────────────

    private String renderPickup(WebOrder order, String branchName, Palette palette) {
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
                WARM_WHITE, palette.pale,
                MUTED_BROWN, palette.dark, branch,
                MUTED_BROWN, palette.pale, palette.primary, statusLabel);
    }

    // ── receipt footer ───────────────────────────────────────────────────

    private String renderFooter(String storeName, Palette palette) {
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
                WARM_WHITE, palette.pale,
                palette.dark,
                MUTED_BROWN,
                MUTED_BROWN,
                palette.pale,
                MUTED_BROWN, escape(storeName),
                java.time.Year.now().getValue());
    }

    // ── branding / colour helpers ────────────────────────────────────────

    static String resolveStoreName(TenantBrandingDto branding, String fallbackBusinessName) {
        return resolveStoreName(branding, fallbackBusinessName, null);
    }

    /**
     * Prefer branding display name, then a real business name, then a title-cased slug.
     * Platform leftovers like {@code Kiosk}/{@code UB} are skipped when a slug is available
     * (e.g. business name still {@code Kiosk} but slug {@code palmart} on palmart.co.ke).
     */
    static String resolveStoreName(
            TenantBrandingDto branding, String fallbackBusinessName, String slug) {
        if (branding != null && branding.displayName() != null && !branding.displayName().isBlank()) {
            String display = branding.displayName().strip();
            if (!isPlatformPlaceholderName(display)) {
                return display;
            }
        }
        if (fallbackBusinessName != null && !fallbackBusinessName.isBlank()
                && !isPlatformPlaceholderName(fallbackBusinessName)) {
            return fallbackBusinessName.strip();
        }
        if (slug != null && !slug.isBlank()) {
            return titleCaseSlug(slug);
        }
        if (fallbackBusinessName != null && !fallbackBusinessName.isBlank()) {
            return fallbackBusinessName.strip();
        }
        return "Your store";
    }

    static boolean isPlatformPlaceholderName(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String n = name.strip();
        // Legacy / platform labels that should not brand a tenant storefront email.
        return n.equalsIgnoreCase("Kiosk")
                || n.equalsIgnoreCase("UB")
                || n.equals("🥬 Palmart");
    }

    static String titleCaseSlug(String slug) {
        String normalized = slug.replace('-', ' ').trim();
        if (normalized.isEmpty()) {
            return "Your store";
        }
        String[] parts = normalized.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.isEmpty() ? "Your store" : sb.toString();
    }

    record Palette(String primary, String accent, String soft, String pale, String dark) {
        static Palette from(TenantBrandingDto branding) {
            String primary = sanitizeHex(
                    branding != null ? branding.primaryColor() : null,
                    DEFAULT_PRIMARY);
            String accent = sanitizeHex(
                    branding != null ? branding.accentColor() : null,
                    DEFAULT_ACCENT);
            return new Palette(
                    primary,
                    accent,
                    mixWithWhite(primary, 0.35),
                    mixWithWhite(primary, 0.82),
                    mixWithBlack(primary, 0.35));
        }
    }

    /** Accept #RGB / #RRGGBB; otherwise fall back. */
    static String sanitizeHex(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.strip();
        if (!s.startsWith("#")) {
            s = "#" + s;
        }
        if (s.length() == 4) {
            char r = s.charAt(1);
            char g = s.charAt(2);
            char b = s.charAt(3);
            s = "#" + r + r + g + g + b + b;
        }
        if (s.length() != 7) {
            return fallback;
        }
        for (int i = 1; i < 7; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return fallback;
            }
        }
        return s.toUpperCase(Locale.ROOT);
    }

    /** Mix hex colour toward white by {@code amount} (0 = unchanged, 1 = white). */
    static String mixWithWhite(String hex, double amount) {
        return mix(hex, 255, 255, 255, amount);
    }

    /** Mix hex colour toward black by {@code amount} (0 = unchanged, 1 = black). */
    static String mixWithBlack(String hex, double amount) {
        return mix(hex, 0, 0, 0, amount);
    }

    private static String mix(String hex, int tr, int tg, int tb, double amount) {
        int[] rgb = parseRgb(hex);
        double a = Math.max(0, Math.min(1, amount));
        int r = (int) Math.round(rgb[0] + (tr - rgb[0]) * a);
        int g = (int) Math.round(rgb[1] + (tg - rgb[1]) * a);
        int b = (int) Math.round(rgb[2] + (tb - rgb[2]) * a);
        return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b);
    }

    private static int[] parseRgb(String hex) {
        String h = sanitizeHex(hex, DEFAULT_PRIMARY);
        return new int[] {
                Integer.parseInt(h.substring(1, 3), 16),
                Integer.parseInt(h.substring(3, 5), 16),
                Integer.parseInt(h.substring(5, 7), 16)
        };
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
