package zelisline.ub.notifications.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import zelisline.ub.storefront.application.OrderConfirmationEmailRenderer;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;

/**
 * Staff digest email for abandoned web carts — same visual system as order
 * confirmation / verification: cool page wash, white card, primary accent bar,
 * tenant logo + colours, and a product preview strip with images.
 */
@Component
public class AbandonedCartDigestEmailRenderer {

    private static final String DEFAULT_PRIMARY = "#1B4332";
    private static final String DEFAULT_ACCENT = "#2D6A4F";

    private static final String PAGE_BG = "#F4F5F4";
    private static final String CARD_BG = "#FFFFFF";
    private static final String BORDER = "#E8EAE8";
    private static final String TEXT = "#14201A";
    private static final String MUTED = "#5C6B63";
    private static final String HAIRLINE = "#EEF0EE";
    private static final String THUMB_BG = "#EEF2EF";

    private static final String FONT_SANS =
            "'DM Sans', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
    private static final String FONT_SERIF =
            "'Cormorant Garamond', Georgia, 'Times New Roman', serif";

    public String renderSubject(TenantBrandingDto branding, String fallbackBusinessName, String slug) {
        String storeName = OrderConfirmationEmailRenderer.resolveStoreName(
                branding, fallbackBusinessName, slug);
        return "Abandoned carts — " + OrderConfirmationEmailRenderer.brandWordmark(storeName);
    }

    public String renderPlainText(
            TenantBrandingDto branding,
            String fallbackBusinessName,
            String slug,
            long cartCount,
            List<ItemPreview> items,
            String actionUrl) {
        String brand = OrderConfirmationEmailRenderer.brandWordmark(
                OrderConfirmationEmailRenderer.resolveStoreName(branding, fallbackBusinessName, slug));
        StringBuilder sb = new StringBuilder();
        sb.append(brand).append(" — abandoned carts\n\n");
        sb.append(cartCount)
                .append(cartCount == 1 ? " cart still has items waiting." : " carts still have items waiting.")
                .append("\n\n");
        if (!items.isEmpty()) {
            sb.append("Top abandoned items:\n");
            for (ItemPreview item : items) {
                sb.append("• ").append(item.name());
                if (item.variantName() != null && !item.variantName().isBlank()) {
                    sb.append(" (").append(item.variantName()).append(")");
                }
                sb.append(" — qty ").append(formatQty(item.quantity()));
                if (item.cartCount() > 0) {
                    sb.append(" across ").append(item.cartCount())
                            .append(item.cartCount() == 1 ? " cart" : " carts");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        sb.append("Review them: ").append(actionUrl != null ? actionUrl : "/").append("\n\n");
        sb.append("— ").append(brand);
        return sb.toString();
    }

    public String renderHtml(
            TenantBrandingDto branding,
            String fallbackBusinessName,
            String slug,
            long cartCount,
            List<ItemPreview> items,
            String actionUrl) {
        String storeName = OrderConfirmationEmailRenderer.resolveStoreName(
                branding, fallbackBusinessName, slug);
        String brand = OrderConfirmationEmailRenderer.brandWordmark(storeName);
        String tagline = OrderConfirmationEmailRenderer.brandTagline(storeName, null);
        Palette palette = Palette.from(branding);
        String logoUrl = branding != null ? blankToNull(branding.logoUrl()) : null;
        String link = actionUrl != null && !actionUrl.isBlank() ? actionUrl : "/";

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Abandoned carts</title>
                %s
                </head>
                <body style="margin:0;padding:0;background-color:%s;font-family:%s;-webkit-font-smoothing:antialiased;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s;padding:40px 16px 56px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background-color:%s;border:1px solid %s;border-radius:4px;overflow:hidden;">
                        %s
                        %s
                        %s
                        %s
                        %s
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(
                renderFontHead(),
                PAGE_BG,
                FONT_SANS,
                PAGE_BG,
                CARD_BG,
                BORDER,
                renderHeader(brand, tagline, logoUrl, palette),
                renderHero(cartCount, items.size(), palette),
                renderItems(items, palette),
                renderCta(link, palette),
                renderFooter(brand));
    }

    private static String renderFontHead() {
        return """
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Cormorant+Garamond:wght@500;600&family=DM+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
                """;
    }

    private String renderHeader(String brand, String tagline, String logoUrl, Palette palette) {
        String logoBlock = logoUrl != null
                ? """
                    <img src="%s" alt="%s" width="44" height="44" style="display:block;width:44px;height:44px;object-fit:contain;border:0;border-radius:10px;margin-bottom:14px;">
                    """.formatted(escape(logoUrl), escape(brand))
                : """
                    <div style="display:inline-block;width:44px;height:44px;border-radius:10px;background-color:%s;text-align:center;line-height:44px;margin-bottom:14px;">
                      <span style="font-family:%s;font-size:15px;font-weight:600;color:#FFFFFF;letter-spacing:0.04em;vertical-align:middle;">%s</span>
                    </div>
                    """.formatted(palette.primary, FONT_SANS, escape(initials(brand)));

        String taglineBlock = tagline == null || tagline.isBlank()
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
                      %s
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
                logoBlock,
                FONT_SANS, TEXT, escape(brand),
                taglineBlock);
    }

    private String renderHero(long cartCount, int previewCount, Palette palette) {
        String countLabel = cartCount == 1
                ? "1 cart still has items waiting"
                : cartCount + " carts still have items waiting";
        String previewHint = previewCount <= 0
                ? "Open the dashboard to follow up before shoppers move on."
                : previewCount == 1
                ? "Here&rsquo;s the item showing up most often."
                : "Here are the items showing up most often.";

        return """
                <tr>
                  <td style="background-color:%s;padding:8px 36px 20px;border-top:1px solid %s;">
                    <div style="font-family:%s;font-size:11px;font-weight:600;color:%s;letter-spacing:0.12em;text-transform:uppercase;margin-bottom:8px;">
                      Daily insight
                    </div>
                    <div style="font-family:%s;font-size:34px;font-weight:500;color:%s;line-height:1.15;letter-spacing:-0.02em;margin-bottom:14px;">
                      Abandoned carts
                    </div>
                    <div style="font-family:%s;font-size:15px;font-weight:500;color:%s;line-height:1.5;margin-bottom:6px;">
                      %s
                    </div>
                    <div style="font-family:%s;font-size:14px;font-weight:400;color:%s;line-height:1.55;">
                      %s
                    </div>
                  </td>
                </tr>
                """.formatted(
                CARD_BG, HAIRLINE,
                FONT_SANS, palette.primary,
                FONT_SERIF, TEXT,
                FONT_SANS, TEXT, escape(countLabel),
                FONT_SANS, MUTED, previewHint);
    }

    private String renderItems(List<ItemPreview> items, Palette palette) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        int shown = 0;
        for (ItemPreview item : items) {
            if (shown >= 8) {
                break;
            }
            rows.append(renderItemRow(item, palette, shown == items.size() - 1 || shown == 7));
            shown++;
        }

        return """
                <tr>
                  <td style="background-color:%s;padding:4px 36px 8px;">
                    <div style="font-family:%s;font-size:11px;font-weight:600;color:%s;text-transform:uppercase;letter-spacing:0.1em;margin-bottom:14px;padding-bottom:10px;border-bottom:1px solid %s;">
                      Left behind
                    </div>
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">
                      %s
                    </table>
                  </td>
                </tr>
                """.formatted(CARD_BG, FONT_SANS, MUTED, BORDER, rows);
    }

    private String renderItemRow(ItemPreview item, Palette palette, boolean last) {
        String border = last ? "none" : "1px solid " + HAIRLINE;
        String thumb = item.imageUrl() != null && !item.imageUrl().isBlank()
                ? """
                    <img src="%s" alt="" width="56" height="56" style="display:block;width:56px;height:56px;object-fit:cover;border:0;border-radius:8px;background-color:%s;">
                    """.formatted(escape(item.imageUrl()), THUMB_BG)
                : """
                    <div style="width:56px;height:56px;border-radius:8px;background-color:%s;text-align:center;line-height:56px;">
                      <span style="font-family:%s;font-size:13px;font-weight:600;color:%s;letter-spacing:0.04em;vertical-align:middle;">%s</span>
                    </div>
                    """.formatted(THUMB_BG, FONT_SANS, palette.primary, escape(initials(item.name())));

        String nameCell = "<div style=\"font-family:" + FONT_SANS
                + ";font-size:14px;font-weight:500;color:" + TEXT + ";line-height:1.35;\">"
                + escape(item.name()) + "</div>";
        if (item.variantName() != null && !item.variantName().isBlank()) {
            nameCell += "<div style=\"font-family:" + FONT_SANS
                    + ";font-size:12px;font-weight:400;color:" + MUTED + ";margin-top:3px;\">"
                    + escape(item.variantName()) + "</div>";
        }

        String meta = "Qty " + escape(formatQty(item.quantity()));
        if (item.cartCount() > 0) {
            meta += " · " + item.cartCount() + (item.cartCount() == 1 ? " cart" : " carts");
        }

        return """
                <tr>
                  <td style="padding:12px 12px 12px 0;width:56px;vertical-align:middle;border-bottom:%s;">
                    %s
                  </td>
                  <td style="padding:12px 8px;vertical-align:middle;border-bottom:%s;">
                    %s
                    <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;margin-top:5px;">
                      %s
                    </div>
                  </td>
                </tr>
                """.formatted(
                border, thumb,
                border, nameCell,
                FONT_SANS, MUTED, meta);
    }

    private String renderCta(String actionUrl, Palette palette) {
        String link = escape(actionUrl);
        return """
                <tr>
                  <td style="background-color:%s;padding:20px 36px 28px;">
                    <table role="presentation" cellpadding="0" cellspacing="0" width="100%%">
                      <tr>
                        <td align="center" style="border-radius:4px;background-color:%s;">
                          <a href="%s" target="_blank" style="display:inline-block;padding:15px 28px;font-family:%s;font-size:15px;font-weight:600;color:#FFFFFF;text-decoration:none;letter-spacing:0.01em;">
                            Review abandoned carts
                          </a>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
                """.formatted(CARD_BG, palette.primary, link, FONT_SANS);
    }

    private String renderFooter(String brand) {
        return """
                <tr>
                  <td style="background-color:%s;padding:24px 36px 32px;border-top:1px solid %s;text-align:left;">
                    <div style="font-family:%s;font-size:13px;font-weight:400;color:%s;line-height:1.65;margin-bottom:8px;">
                      A gentle nudge now can turn these into real orders.
                    </div>
                    <div style="font-family:%s;font-size:11px;font-weight:400;color:#9AA39D;">
                      %s &nbsp;&middot;&nbsp; Abandoned cart digest &nbsp;&middot;&nbsp; %s
                    </div>
                  </td>
                </tr>
                """.formatted(
                CARD_BG, BORDER,
                FONT_SANS, MUTED,
                FONT_SANS,
                escape(brand),
                java.time.Year.now().getValue());
    }

    public record ItemPreview(
            String itemId,
            String name,
            String variantName,
            String imageUrl,
            BigDecimal quantity,
            long cartCount
    ) {
    }

    record Palette(String primary, String accent) {
        static Palette from(TenantBrandingDto branding) {
            String primary = OrderConfirmationEmailRenderer.sanitizeHex(
                    branding != null ? branding.primaryColor() : null,
                    DEFAULT_PRIMARY);
            String accent = OrderConfirmationEmailRenderer.sanitizeHex(
                    branding != null ? branding.accentColor() : null,
                    DEFAULT_ACCENT);
            return new Palette(primary, accent);
        }
    }

    static String formatQty(BigDecimal qty) {
        return OrderConfirmationEmailRenderer.formatQty(qty);
    }

    static String escape(String s) {
        return OrderConfirmationEmailRenderer.escape(s);
    }

    static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.strip();
    }

    static String initials(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase(Locale.ROOT);
        }
        String one = parts[0];
        return one.length() >= 2
                ? one.substring(0, 2).toUpperCase(Locale.ROOT)
                : one.toUpperCase(Locale.ROOT);
    }
}
