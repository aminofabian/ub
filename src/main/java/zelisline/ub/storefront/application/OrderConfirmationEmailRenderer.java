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
 * Renders a clean, professional HTML order confirmation email with inline CSS
 * suitable for the widest range of email clients. Colours and store name come
 * from tenant branding when set.
 */
@Component
public class OrderConfirmationEmailRenderer {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy 'at' hh:mm a").withZone(ZoneId.of("Africa/Nairobi"));

    // ── default palette (used when tenant colours are unset) ─────────────
    private static final String DEFAULT_PRIMARY = "#1B4332";
    private static final String DEFAULT_ACCENT = "#2D6A4F";

    /** Page wash — cool neutral, not cream. */
    private static final String PAGE_BG = "#F4F5F4";
    private static final String CARD_BG = "#FFFFFF";
    private static final String BORDER = "#E8EAE8";
    private static final String TEXT = "#14201A";
    private static final String MUTED = "#5C6B63";
    private static final String HAIRLINE = "#EEF0EE";

    /** Matches verification email / frontend {@code --font-dm-sans}. */
    private static final String FONT_SANS =
            "'DM Sans', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
    /** Display for the thank-you line — matches {@code --font-cormorant}. */
    private static final String FONT_SERIF =
            "'Cormorant Garamond', Georgia, 'Times New Roman', serif";
    private static final String FONT_MONO =
            "'SF Mono', Menlo, Consolas, 'Courier New', monospace";

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
        String storeName = resolveStoreName(branding, fallbackBusinessName, businessSlug, branchName);
        Palette palette = Palette.from(branding);
        String location = cleanLocation(branchName);

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Order Confirmed</title>
                %s
                </head>
                <body style="margin:0;padding:0;background-color:%s;font-family:%s;-webkit-font-smoothing:antialiased;">
                %s
                </body>
                </html>
                """.formatted(
                renderFontHead(),
                PAGE_BG,
                FONT_SANS,
                renderBody(order, lines, location, storeName, palette));
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

    private static String renderFontHead() {
        return """
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Cormorant+Garamond:wght@500;600&family=DM+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
                """;
    }

    // ── body ─────────────────────────────────────────────────────────────

    private String renderBody(
            WebOrder order,
            List<WebOrderLine> lines,
            String location,
            String storeName,
            Palette palette) {
        String brand = brandWordmark(storeName);
        String tagline = brandTagline(storeName, location);

        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s;padding:40px 16px 56px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background-color:%s;border:1px solid %s;border-radius:4px;overflow:hidden;">
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
                PAGE_BG,
                CARD_BG,
                BORDER,
                renderHeader(brand, tagline, palette),
                renderHero(order, palette),
                renderLinesTable(lines),
                renderTotals(order),
                renderPickup(location, palette),
                renderFooter(brand, location));
    }

    // ── header ───────────────────────────────────────────────────────────

    private String renderHeader(String brand, String tagline, Palette palette) {
        String taglineBlock = tagline.isBlank()
                ? ""
                : """
                    <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;letter-spacing:0.02em;margin-top:6px;">
                      %s
                    </div>
                    """.formatted(FONT_SANS, MUTED, escape(tagline));

        return """
                <tr>
                  <td style="background-color:%s;padding:0;">
                    <div style="height:3px;background-color:%s;line-height:3px;font-size:0;">&nbsp;</div>
                    <div style="padding:28px 36px 24px;text-align:left;">
                      <div style="font-family:%s;font-size:20px;font-weight:600;color:%s;letter-spacing:-0.02em;line-height:1.2;">
                        %s
                      </div>
                      %s
                    </div>
                  </td>
                </tr>
                """.formatted(
                CARD_BG,
                palette.primary,
                FONT_SANS, TEXT, escape(brand),
                taglineBlock);
    }

    // ── hero section ─────────────────────────────────────────────────────

    private String renderHero(WebOrder order, Palette palette) {
        String dateStr = order.getCreatedAt() != null
                ? DATE_FMT.format(order.getCreatedAt())
                : "";

        return """
                <tr>
                  <td style="background-color:%s;padding:8px 36px 28px;border-top:1px solid %s;">
                    <div style="font-family:%s;font-size:11px;font-weight:600;color:%s;letter-spacing:0.12em;text-transform:uppercase;margin-bottom:8px;">
                      Order confirmed
                    </div>
                    <div style="font-family:%s;font-size:34px;font-weight:500;color:%s;line-height:1.15;letter-spacing:-0.02em;margin-bottom:18px;">
                      Thank you
                    </div>
                    <div style="font-family:%s;font-size:12px;font-weight:500;color:%s;letter-spacing:0.02em;margin-bottom:4px;">
                      Order ID
                    </div>
                    <div style="font-family:%s;font-size:12px;font-weight:500;color:%s;letter-spacing:0.01em;word-break:break-all;margin-bottom:10px;">
                      %s
                    </div>
                    <div style="font-family:%s;font-size:13px;font-weight:400;color:%s;">
                      Placed on %s
                    </div>
                  </td>
                </tr>
                """.formatted(
                CARD_BG, HAIRLINE,
                FONT_SANS, palette.primary,
                FONT_SERIF, TEXT,
                FONT_SANS, MUTED,
                FONT_MONO, TEXT, escape(order.getId()),
                FONT_SANS, MUTED, dateStr);
    }

    // ── lines table ──────────────────────────────────────────────────────

    private String renderLinesTable(List<WebOrderLine> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <tr>
                  <td style="background-color:%s;padding:8px 36px 0;">
                    <div style="font-family:%s;font-size:11px;font-weight:600;color:%s;text-transform:uppercase;letter-spacing:0.1em;margin-bottom:12px;padding-bottom:10px;border-bottom:1px solid %s;">
                      Order details
                    </div>
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">
                      <tr>
                        <td style="padding:0 0 8px;font-family:%s;font-size:10px;font-weight:600;color:%s;text-transform:uppercase;letter-spacing:0.08em;">Item</td>
                        <td style="padding:0 0 8px;font-family:%s;font-size:10px;font-weight:600;color:%s;text-transform:uppercase;letter-spacing:0.08em;width:40px;text-align:center;">Qty</td>
                        <td style="padding:0 0 8px;font-family:%s;font-size:10px;font-weight:600;color:%s;text-transform:uppercase;letter-spacing:0.08em;text-align:right;">Amount</td>
                      </tr>
                """.formatted(
                CARD_BG,
                FONT_SANS, MUTED, BORDER,
                FONT_SANS, MUTED,
                FONT_SANS, MUTED,
                FONT_SANS, MUTED));

        for (WebOrderLine line : lines) {
            String nameCell = "<span style=\"font-family:" + FONT_SANS
                    + ";font-weight:500;color:" + TEXT + ";\">"
                    + escape(line.getItemName()) + "</span>";
            if (line.getVariantName() != null && !line.getVariantName().isBlank()) {
                nameCell += "<br><span style=\"font-family:" + FONT_SANS
                        + ";font-size:12px;font-weight:400;color:" + MUTED + ";\">"
                        + escape(line.getVariantName()) + "</span>";
            }
            sb.append("""
                      <tr>
                        <td style="padding:12px 8px 12px 0;font-size:14px;border-bottom:1px solid %s;vertical-align:top;">%s</td>
                        <td style="padding:12px 0;font-family:%s;font-size:14px;font-weight:400;color:%s;text-align:center;border-bottom:1px solid %s;vertical-align:top;">%s</td>
                        <td style="padding:12px 0 12px 8px;font-family:%s;font-size:14px;font-weight:500;color:%s;text-align:right;border-bottom:1px solid %s;vertical-align:top;white-space:nowrap;">%s</td>
                      </tr>
                    """.formatted(
                    HAIRLINE, nameCell,
                    FONT_SANS, TEXT, HAIRLINE, formatQty(line.getQuantity()),
                    FONT_SANS, TEXT, HAIRLINE, formatKes(line.getLineTotal())));
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

        return """
                <tr>
                  <td style="background-color:%s;padding:16px 36px 8px;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                      <tr>
                        <td style="padding:4px 0;font-family:%s;font-size:13px;font-weight:500;color:%s;">Total</td>
                        <td style="padding:4px 0;font-family:%s;font-size:16px;font-weight:600;color:%s;text-align:right;">
                          %s
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
                """.formatted(CARD_BG, FONT_SANS, MUTED, FONT_SANS, TEXT, formatKes(grand));
    }

    // ── pickup + status ──────────────────────────────────────────────────

    private String renderPickup(String location, Palette palette) {
        String branch = location.isBlank() ? "Store" : location;
        String statusLabel = "Pending payment";

        return """
                <tr>
                  <td style="background-color:%s;padding:24px 36px 28px;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-top:1px solid %s;padding-top:0;">
                      <tr>
                        <td colspan="2" style="height:20px;font-size:0;line-height:0;">&nbsp;</td>
                      </tr>
                      <tr>
                        <td style="vertical-align:top;width:50%%;padding-right:16px;">
                          <div style="font-family:%s;font-size:10px;font-weight:600;color:%s;text-transform:uppercase;letter-spacing:0.1em;margin-bottom:6px;">
                            Pickup
                          </div>
                          <div style="font-family:%s;font-size:15px;font-weight:500;color:%s;">
                            %s
                          </div>
                        </td>
                        <td style="vertical-align:top;width:50%%;text-align:right;padding-left:16px;">
                          <div style="font-family:%s;font-size:10px;font-weight:600;color:%s;text-transform:uppercase;letter-spacing:0.1em;margin-bottom:6px;">
                            Status
                          </div>
                          <div style="font-family:%s;font-size:14px;font-weight:500;color:%s;">
                            %s
                          </div>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
                """.formatted(
                CARD_BG, BORDER,
                FONT_SANS, MUTED,
                FONT_SANS, TEXT, escape(branch),
                FONT_SANS, MUTED,
                FONT_SANS, palette.primary, statusLabel);
    }

    // ── receipt footer ───────────────────────────────────────────────────

    private String renderFooter(String brand, String location) {
        String credit = location.isBlank()
                ? escape(brand)
                : escape(brand) + " · " + escape(location);

        return """
                <tr>
                  <td style="background-color:%s;padding:28px 36px 32px;border-top:1px solid %s;text-align:left;">
                    <div style="font-family:%s;font-size:13px;font-weight:400;color:%s;line-height:1.65;margin-bottom:6px;">
                      Thank you for shopping with us.
                    </div>
                    <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;line-height:1.65;margin-bottom:4px;">
                      Staff may contact you by phone or WhatsApp to confirm your order.
                    </div>
                    <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;line-height:1.65;margin-bottom:20px;">
                      Questions? Reply to this email.
                    </div>
                    <div style="font-family:%s;font-size:11px;font-weight:400;color:#9AA39D;">
                      %s &nbsp;&middot;&nbsp; %s
                    </div>
                  </td>
                </tr>
                """.formatted(
                CARD_BG, BORDER,
                FONT_SANS, TEXT,
                FONT_SANS, MUTED,
                FONT_SANS, MUTED,
                FONT_SANS,
                credit,
                java.time.Year.now().getValue());
    }

    // ── branding / name helpers ──────────────────────────────────────────

    static String resolveStoreName(TenantBrandingDto branding, String fallbackBusinessName) {
        return resolveStoreName(branding, fallbackBusinessName, null, null);
    }

    static String resolveStoreName(
            TenantBrandingDto branding, String fallbackBusinessName, String slug) {
        return resolveStoreName(branding, fallbackBusinessName, slug, null);
    }

    /**
     * Prefer branding display name, then a real business name, then a title-cased slug.
     * Applies {@code [Area]} / {@code [Country]} / {@code [Name]} placeholders when present
     * (e.g. SEO title mistakenly saved as display name).
     */
    static String resolveStoreName(
            TenantBrandingDto branding,
            String fallbackBusinessName,
            String slug,
            String location) {
        String raw = null;
        if (branding != null && branding.displayName() != null && !branding.displayName().isBlank()) {
            String display = branding.displayName().strip();
            if (!isPlatformPlaceholderName(display)) {
                raw = display;
            }
        }
        if (raw == null && fallbackBusinessName != null && !fallbackBusinessName.isBlank()
                && !isPlatformPlaceholderName(fallbackBusinessName)) {
            raw = fallbackBusinessName.strip();
        }
        if (raw == null && slug != null && !slug.isBlank()) {
            raw = titleCaseSlug(slug);
        }
        if (raw == null && fallbackBusinessName != null && !fallbackBusinessName.isBlank()) {
            raw = fallbackBusinessName.strip();
        }
        if (raw == null) {
            raw = "Your store";
        }
        return applySeoPlaceholders(raw, location, brandWordmark(raw));
    }

    /**
     * Short brand for headers / subjects — text before {@code |} when the name looks
     * like an SEO title ({@code Palmart | Groceries…}).
     */
    static String brandWordmark(String storeName) {
        if (storeName == null || storeName.isBlank()) {
            return "Your store";
        }
        int pipe = storeName.indexOf('|');
        if (pipe > 0) {
            String left = storeName.substring(0, pipe).trim();
            if (!left.isBlank()) {
                return left;
            }
        }
        return storeName.trim();
    }

    /**
     * Optional subtitle under the wordmark — prefers text after {@code |}, else
     * a quiet “in {location}” line.
     */
    static String brandTagline(String storeName, String location) {
        if (storeName != null) {
            int pipe = storeName.indexOf('|');
            if (pipe >= 0 && pipe < storeName.length() - 1) {
                String right = storeName.substring(pipe + 1).trim();
                if (!right.isBlank()) {
                    return right;
                }
            }
        }
        String loc = cleanLocation(location);
        if (!loc.isBlank()) {
            return "Groceries & essentials · " + loc;
        }
        return "";
    }

    /**
     * Substitutes [Area], [Country], [Name] (and {area}/{country}/{name}) the same
     * way storefront SEO templates do on the frontend.
     */
    static String applySeoPlaceholders(String template, String location, String name) {
        if (template == null || template.isBlank()) {
            return template;
        }
        String area = cleanLocation(location);
        String country = "Kenya";
        String display = name != null && !name.isBlank() ? name.trim() : "";

        String out = template;
        out = out.replaceAll("(?i)\\[Area\\]|\\{area\\}", area);
        out = out.replaceAll("(?i)\\[Country\\]|\\{country\\}", country);
        out = out.replaceAll("(?i)\\[Name\\]|\\{name\\}|\\{displayName\\}", java.util.regex.Matcher.quoteReplacement(display));
        out = out.replaceAll("(?i)\\bin\\s*,", "in");
        out = out.replaceAll("\\s{2,}", " ");
        out = out.replaceAll("\\s+,", ",");
        out = out.replaceAll(",\\s*,", ",");
        out = out.trim().replaceAll("^[,|]\\s*", "").replaceAll("\\s*[,|]$", "");
        return out;
    }

    static String cleanLocation(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim().replaceAll("(?i)\\s+branch$", "").trim();
        if (value.contains(",")) {
            String first = value.split(",", 2)[0].trim();
            if (!first.isBlank() && first.length() <= 48) {
                value = first;
            }
        }
        if (value.length() > 64) {
            value = value.substring(0, 64).trim();
        }
        return value;
    }

    static boolean isPlatformPlaceholderName(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String n = name.strip();
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

    record Palette(String primary, String accent) {
        static Palette from(TenantBrandingDto branding) {
            String primary = sanitizeHex(
                    branding != null ? branding.primaryColor() : null,
                    DEFAULT_PRIMARY);
            String accent = sanitizeHex(
                    branding != null ? branding.accentColor() : null,
                    DEFAULT_ACCENT);
            return new Palette(primary, accent);
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
