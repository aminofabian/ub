package zelisline.ub.onboarding.sequence.application;

import java.util.List;

import zelisline.ub.onboarding.sequence.MerchantOnboardingStep;

/**
 * Email craft helpers for the onboarding sequence: help-article deep links
 * ("See how") and optional screenshot URLs (static /help assets or CDN overrides).
 */
public final class MerchantOnboardingEmailCraft {

    public record Guide(String path, String label) {
    }

    public record Shot(String path, String alt) {
    }

    private MerchantOnboardingEmailCraft() {
    }

    public static List<Guide> guidesFor(MerchantOnboardingStep step) {
        if (step == null) {
            return List.of();
        }
        return switch (step) {
            case M1_FILL_SHELF -> List.of(
                    new Guide(
                            "/help/merchants/inventory/how-to-add-products",
                            "How to add products (catalog, families, variants)"),
                    new Guide(
                            "/help/merchants/getting-started/add-your-first-products",
                            "Add your first products"));
            case M2_SIZES, N1_LOOKALIKE -> List.of(
                    new Guide(
                            "/help/merchants/inventory/how-to-add-products",
                            "Families, variants, and packages"));
            case M3_MONEY_LOOP -> List.of(
                    new Guide(
                            "/help/merchants/suppliers-supplies/complete-supplier-flow",
                            "Supplier → order → supply (full loop)"),
                    new Guide(
                            "/help/merchants/suppliers-supplies/record-a-supply",
                            "Record a supply so stock rises"));
            case M4_FIRST_SALE -> List.of(
                    new Guide(
                            "/help/merchants/getting-started/open-the-cashier-for-the-first-time",
                            "Open the cashier / first sale"),
                    new Guide(
                            "/help/merchants/mpesa-payments/configure-payment-settings",
                            "Connect M-Pesa"));
            case M4_FALLBACK -> List.of(
                    new Guide(
                            "/help/merchants/getting-started/add-your-first-products",
                            "Stock your shelf"),
                    new Guide(
                            "/help/merchants/getting-started/open-the-cashier-for-the-first-time",
                            "Open the till"));
            case M5_GO_LIVE -> List.of(
                    new Guide(
                            "/help/merchants/storefront/set-up-your-online-store",
                            "Set up your online store"),
                    new Guide(
                            "/help/merchants/storefront/brand-your-storefront",
                            "Brand your storefront"));
            case M6_TEAM -> List.of(
                    new Guide(
                            "/help/merchants/staff-branches/invite-your-first-staff",
                            "Invite your first staff"),
                    new Guide(
                            "/help/merchants/staff-branches/user-roles-add-users",
                            "Roles and PINs"));
            case W_WEEK_CHECKIN -> List.of(
                    new Guide(
                            "/help/merchants/getting-started/get-the-most-from-kiosk",
                            "Get the most from Kiosk"));
            case N2_CLOSE_SHIFT -> List.of(
                    new Guide(
                            "/help/merchants/getting-started/open-the-cashier-for-the-first-time",
                            "Shifts and closing the till"));
            case N4_WEB_ORDER -> List.of(
                    new Guide(
                            "/help/merchants/storefront/set-up-your-online-store",
                            "Web orders on your storefront"));
            case M0_WELCOME -> List.of();
        };
    }

    /**
     * Default static help screenshot (relative to public host). Empty when no asset fits.
     * Prefer real /help/*.svg already shipped in the frontend public folder.
     */
    public static Shot defaultShot(MerchantOnboardingStep step) {
        if (step == null) {
            return null;
        }
        return switch (step) {
            case M1_FILL_SHELF -> new Shot("/help/add-product-drawer.svg", "Add a product from the catalog");
            case M2_SIZES, N1_LOOKALIKE -> new Shot(
                    "/help/add-product-group-drawer.svg", "Create a product family with variants");
            case M3_MONEY_LOOP -> new Shot("/help/new-supply-drawer.svg", "Post a supply so stock rises");
            case M4_FIRST_SALE, N2_CLOSE_SHIFT -> new Shot(
                    "/help/welcome-dashboard.svg", "Your hub after the first sale");
            case M4_FALLBACK -> new Shot("/help/add-product-drawer.svg", "Stock the shelf to start selling");
            case M5_GO_LIVE -> new Shot("/help/welcome-dashboard.svg", "Publish your online store");
            case M6_TEAM -> new Shot("/help/invite-user-drawer.svg", "Invite staff with a PIN");
            case W_WEEK_CHECKIN -> new Shot("/help/welcome-dashboard.svg", "Your week on Kiosk");
            case N4_WEB_ORDER -> new Shot("/help/welcome-dashboard.svg", "Fulfil a web order");
            case M0_WELCOME -> null;
        };
    }

    public static String contrastTableHtml() {
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="margin:4px 0 18px;border-collapse:separate;border-spacing:8px 0;">
                  <tr>
                    <td width="50%%" valign="top" style="background-color:#FEF2F2;border:1px solid #FECACA;border-radius:4px;padding:12px 14px;">
                      <div style="font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:11px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:#B91C1C;margin:0 0 6px;">Wrong</div>
                      <div style="font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:13px;line-height:1.45;color:#7F1D1D;">
                        Three separate products:<br>&ldquo;Coke 500ml&rdquo;, &ldquo;Coke 1L&rdquo;, &ldquo;Coke crate&rdquo;
                      </div>
                    </td>
                    <td width="50%%" valign="top" style="background-color:#F0FDF4;border:1px solid #BBF7D0;border-radius:4px;padding:12px 14px;">
                      <div style="font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:11px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:#15803D;margin:0 0 6px;">Right</div>
                      <div style="font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:13px;line-height:1.45;color:#14532D;">
                        Family <strong>Coca-Cola</strong> → variants <strong>500ml</strong>, <strong>1L</strong> → optional crate/pack that depletes base units
                      </div>
                    </td>
                  </tr>
                </table>
                """;
    }

    public static String appendGuidesToPlain(String lessonPlain, String helpHost, List<Guide> guides) {
        String base = lessonPlain == null ? "" : lessonPlain.strip();
        if (guides == null || guides.isEmpty()) {
            return base;
        }
        String host = trailingSlashTrim(helpHost);
        StringBuilder sb = new StringBuilder(base);
        sb.append("\n\nSee how (annotated walkthroughs):");
        for (Guide g : guides) {
            sb.append("\n• ").append(g.label()).append(" — ").append(host).append(g.path());
        }
        return sb.toString();
    }

    /**
     * Inner email body: optional shot + lesson paragraphs + See-how callout.
     * Does not include the Kiosk card shell.
     */
    public static String innerHtml(
            String lessonPlain,
            String helpHost,
            List<Guide> guides,
            Shot shot,
            String absoluteShotUrl
    ) {
        return innerHtml(lessonPlain, helpHost, guides, shot, absoluteShotUrl, null);
    }

    public static String innerHtml(
            String lessonPlain,
            String helpHost,
            List<Guide> guides,
            Shot shot,
            String absoluteShotUrl,
            String middleHtml
    ) {
        StringBuilder sb = new StringBuilder();
        String imgUrl = absoluteShotUrl;
        String alt = shot != null ? shot.alt() : "Screenshot";
        if (imgUrl == null || imgUrl.isBlank()) {
            if (shot != null && shot.path() != null && !shot.path().isBlank()) {
                imgUrl = trailingSlashTrim(helpHost) + shot.path();
            }
        }
        if (imgUrl != null && !imgUrl.isBlank()) {
            sb.append("<div style=\"margin:0 0 18px;\">")
                    .append("<img src=\"")
                    .append(escapeAttr(imgUrl))
                    .append("\" alt=\"")
                    .append(escapeAttr(alt))
                    .append("\" width=\"520\" style=\"display:block;width:100%;max-width:520px;")
                    .append("height:auto;border:1px solid #E8EAE8;border-radius:4px;\"/>")
                    .append("</div>");
        }
        sb.append(MerchantOnboardingMessageRenderer.toHtmlParagraphs(lessonPlain));
        if (middleHtml != null && !middleHtml.isBlank()) {
            sb.append(middleHtml);
        }
        if (guides != null && !guides.isEmpty()) {
            String host = trailingSlashTrim(helpHost);
            sb.append("<div style=\"margin:8px 0 4px;padding:14px 16px;border:1px solid #E8EAE8;")
                    .append("border-left:3px solid #28A745;background-color:#F8FBF8;\">")
                    .append("<div style=\"font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;")
                    .append("font-size:12px;font-weight:600;color:#14201A;letter-spacing:0.06em;")
                    .append("text-transform:uppercase;margin:0 0 8px;\">See how</div>");
            for (Guide g : guides) {
                String href = host + g.path();
                sb.append("<div style=\"font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;")
                        .append("font-size:14px;line-height:1.5;margin:0 0 6px;\">")
                        .append("<a href=\"")
                        .append(escapeAttr(href))
                        .append("\" style=\"color:#28A745;font-weight:600;text-decoration:underline;\">")
                        .append(escape(g.label()))
                        .append("</a></div>");
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    static String trailingSlashTrim(String url) {
        if (url == null || url.isBlank()) {
            return "https://kiosk.ke";
        }
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String escape(String s) {
        return MerchantOnboardingMessageRenderer.escape(s == null ? "" : s);
    }

    private static String escapeAttr(String s) {
        return escape(s).replace("\"", "&quot;");
    }
}
