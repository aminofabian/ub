package zelisline.ub.onboarding.sequence.application;

import java.util.ArrayList;
import java.util.List;

import zelisline.ub.onboarding.sequence.MerchantOnboardingStep;

/**
 * Email craft for onboarding mini-guides: sectioned lessons, captioned screenshots,
 * and help-article deep links ("See how").
 */
public final class MerchantOnboardingEmailCraft {

    public record Guide(String path, String label) {
    }

    /** Screenshot with a caption that teaches what to look at / click. */
    public record Shot(String path, String alt, String caption) {
        public Shot(String path, String alt) {
            this(path, alt, alt);
        }
    }

    /**
     * Structured mini-guide body. Every teaching email should fill these fields
     * so the reader finishes knowing what, why, who/when, how, and what to do next.
     */
    public record GuideLesson(
            String greetingLine,
            String problem,
            String whatItDoes,
            String whyItMatters,
            List<String> howSteps,
            List<Shot> shots,
            String example,
            String beforeYouStart,
            String proTip,
            String closing
    ) {
        public GuideLesson {
            howSteps = howSteps == null ? List.of() : List.copyOf(howSteps);
            shots = shots == null ? List.of() : List.copyOf(shots);
        }
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

    /** Primary + secondary screenshots for a step (with captions). */
    public static List<Shot> shotsFor(MerchantOnboardingStep step) {
        if (step == null) {
            return List.of();
        }
        return switch (step) {
            case M1_FILL_SHELF -> List.of(
                    new Shot(
                            "/help/onboarding/m1-fill-shelf.png",
                            "Global catalog on Kiosk",
                            "Products → Global catalog. Pick a starter pack, review the list, then Import — barcodes and names land on your shelf."));
            case M2_SIZES, N1_LOOKALIKE -> List.of(
                    new Shot(
                            "/help/onboarding/m2-sizes.jpg",
                            "Product family",
                            "One family (e.g. Coca-Cola) holds every size. Open the product, then add variants — don’t create three separate products."),
                    new Shot(
                            "/help/onboarding/m2-variant.jpg",
                            "Variants on a family",
                            "Each size or pack is a variant under the same family. Stock, cost, and sales stay tied together."));
            case M3_MONEY_LOOP -> List.of(
                    new Shot(
                            "/help/onboarding/m3-supplier.png",
                            "Add a supplier",
                            "Suppliers → Add. Name + phone is enough — that phone becomes the WhatsApp number for orders."),
                    new Shot(
                            "/help/onboarding/m3-money-loop.png",
                            "Post a supply",
                            "Supplies → record the delivery. Stock only rises when you post the supply — not when you save an order."));
            case M4_FIRST_SALE, N2_CLOSE_SHIFT -> List.of(
                    new Shot(
                            "/help/onboarding/m4-first-sale.png",
                            "Shifts and closing the till",
                            "Shifts → close tonight’s shift. Enter the closing count so float, sales, and cash reconcile into a variance you can trust."));
            case M4_FALLBACK -> List.of(
                    new Shot(
                            "/help/onboarding/m4-fallback.png",
                            "Stock the shelf to start selling",
                            "If the shelf is empty, start at Global catalog. If it’s stocked, open a shift and take the first sale on Cashier."));
            case M5_GO_LIVE -> List.of(
                    new Shot(
                            "/help/onboarding/m5-go-live.png",
                            "Publish your online store",
                            "Business → Settings (storefront). Publish, then Brand for logo and colours. Same stock count as the till."));
            case M6_TEAM -> List.of(
                    new Shot(
                            "/help/onboarding/m6-team.png",
                            "Invite staff with a PIN",
                            "Users → Invite. Each cashier gets their own till PIN so you can see who sold what."));
            case W_WEEK_CHECKIN -> List.of(
                    new Shot(
                            "/help/onboarding/w-week-checkin.png",
                            "Your business hub",
                            "Business hub shows how the week landed — products, suppliers, sales. Use it to pick what to restock next."));
            case N4_WEB_ORDER -> List.of(
                    new Shot(
                            "/help/onboarding/m5-go-live.png",
                            "Web orders",
                            "Storefront → Web orders. Fulfil from the same catalog — every line depletes till stock."));
            case M0_WELCOME -> List.of();
        };
    }

    /** @deprecated prefer {@link #shotsFor(MerchantOnboardingStep)} */
    @Deprecated
    public static Shot defaultShot(MerchantOnboardingStep step) {
        List<Shot> shots = shotsFor(step);
        return shots.isEmpty() ? null : shots.getFirst();
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

    public static String renderGuidePlain(GuideLesson lesson, String helpHost, List<Guide> guides) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(lesson.greetingLine())) {
            sb.append(lesson.greetingLine().trim()).append("\n\n");
        }
        appendPlainSection(sb, "The problem", lesson.problem());
        appendPlainSection(sb, "What this does", lesson.whatItDoes());
        appendPlainSection(sb, "Why it matters", lesson.whyItMatters());
        if (lesson.howSteps() != null && !lesson.howSteps().isEmpty()) {
            sb.append("How it works\n");
            int i = 1;
            for (String step : lesson.howSteps()) {
                sb.append(i++).append(". ").append(step.trim()).append('\n');
            }
            sb.append('\n');
        }
        if (lesson.shots() != null && !lesson.shots().isEmpty()) {
            sb.append("What to look for on screen\n");
            for (Shot shot : lesson.shots()) {
                if (notBlank(shot.caption())) {
                    sb.append("• ").append(shot.caption().trim()).append('\n');
                }
            }
            sb.append('\n');
        }
        appendPlainSection(sb, "Example", lesson.example());
        appendPlainSection(sb, "Before you start", lesson.beforeYouStart());
        appendPlainSection(sb, "Pro tip", lesson.proTip());
        if (notBlank(lesson.closing())) {
            sb.append(lesson.closing().trim()).append("\n\n");
        }
        return appendGuidesToPlain(sb.toString().strip(), helpHost, guides);
    }

    /** Chat-friendly excerpt: still teaches, fits a support bubble. */
    public static String renderGuideChat(GuideLesson lesson, String ctaPath) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(lesson.greetingLine())) {
            sb.append(lesson.greetingLine().trim()).append("\n\n");
        }
        if (notBlank(lesson.problem())) {
            sb.append(lesson.problem().trim()).append("\n\n");
        }
        if (notBlank(lesson.whatItDoes())) {
            sb.append(lesson.whatItDoes().trim()).append("\n\n");
        }
        if (lesson.howSteps() != null && !lesson.howSteps().isEmpty()) {
            sb.append("How to do it:\n");
            int i = 1;
            for (String step : lesson.howSteps()) {
                sb.append(i++).append(". ").append(step.trim()).append('\n');
            }
            sb.append('\n');
        }
        if (notBlank(lesson.example())) {
            sb.append("Example: ").append(lesson.example().trim()).append("\n\n");
        }
        if (notBlank(lesson.proTip())) {
            sb.append("Pro tip: ").append(lesson.proTip().trim()).append("\n\n");
        }
        if (ctaPath != null && !ctaPath.isBlank()) {
            sb.append("→ ").append(ctaPath.trim());
        }
        String out = sb.toString().strip();
        if (out.length() > 3800) {
            return out.substring(0, 3797) + "…";
        }
        return out;
    }

    public static String renderGuideHtml(
            GuideLesson lesson,
            String helpHost,
            List<Guide> guides,
            String absolutePrimaryShotOverride,
            String middleHtml
    ) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(lesson.greetingLine())) {
            sb.append(para(lesson.greetingLine()));
        }
        sb.append(section("The problem", lesson.problem()));
        sb.append(section("What this does", lesson.whatItDoes()));
        sb.append(section("Why it matters", lesson.whyItMatters()));
        sb.append(stepsHtml(lesson.howSteps()));
        if (middleHtml != null && !middleHtml.isBlank()) {
            sb.append(middleHtml);
        }
        sb.append(shotsHtml(lesson.shots(), helpHost, absolutePrimaryShotOverride));
        sb.append(section("Example", lesson.example()));
        sb.append(section("Before you start", lesson.beforeYouStart()));
        sb.append(section("Pro tip", lesson.proTip()));
        if (notBlank(lesson.closing())) {
            sb.append(para(lesson.closing()));
        }
        sb.append(seeHowHtml(helpHost, guides));
        return sb.toString();
    }

    /**
     * Legacy path: optional shot + lesson paragraphs + See-how.
     * Prefer {@link #renderGuideHtml} for new mini-guides.
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
        List<Shot> shots = new ArrayList<>();
        if (shot != null) {
            shots.add(shot);
        }
        GuideLesson lesson = new GuideLesson(
                null,
                null,
                lessonPlain,
                null,
                List.of(),
                shots,
                null,
                null,
                null,
                null);
        // Preserve old behaviour: full plain as body paragraphs, shot first.
        StringBuilder sb = new StringBuilder();
        sb.append(shotsHtml(shots, helpHost, absoluteShotUrl));
        sb.append(MerchantOnboardingMessageRenderer.toHtmlParagraphs(lessonPlain));
        if (middleHtml != null && !middleHtml.isBlank()) {
            sb.append(middleHtml);
        }
        sb.append(seeHowHtml(helpHost, guides));
        return sb.toString();
    }

    private static void appendPlainSection(StringBuilder sb, String title, String body) {
        if (!notBlank(body)) {
            return;
        }
        sb.append(title).append('\n').append(body.trim()).append("\n\n");
    }

    private static String section(String title, String body) {
        if (!notBlank(body)) {
            return "";
        }
        return """
                <div style="margin:0 0 18px;">
                  <div style="font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:12px;font-weight:700;letter-spacing:0.06em;text-transform:uppercase;color:#14201A;margin:0 0 8px;">%s</div>
                  %s
                </div>
                """.formatted(escape(title), para(body));
    }

    private static String stepsHtml(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <div style="margin:0 0 18px;">
                  <div style="font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:12px;font-weight:700;letter-spacing:0.06em;text-transform:uppercase;color:#14201A;margin:0 0 10px;">How it works</div>
                """);
        int i = 1;
        for (String step : steps) {
            sb.append("""
                    <div style="display:flex;gap:10px;margin:0 0 10px;font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:14px;line-height:1.55;color:#5C6B63;">
                      <div style="flex-shrink:0;width:22px;height:22px;border-radius:999px;background:#28A745;color:#fff;font-size:12px;font-weight:700;line-height:22px;text-align:center;">%d</div>
                      <div>%s</div>
                    </div>
                    """.formatted(i++, escape(step.trim()).replace("\n", "<br>")));
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String shotsHtml(List<Shot> shots, String helpHost, String absolutePrimaryOverride) {
        if (shots == null || shots.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <div style="margin:0 0 18px;">
                  <div style="font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:12px;font-weight:700;letter-spacing:0.06em;text-transform:uppercase;color:#14201A;margin:0 0 10px;">On your screen</div>
                """);
        for (int i = 0; i < shots.size(); i++) {
            Shot shot = shots.get(i);
            String imgUrl = null;
            if (i == 0 && absolutePrimaryOverride != null && !absolutePrimaryOverride.isBlank()) {
                imgUrl = absolutePrimaryOverride.trim();
            } else if (shot.path() != null && !shot.path().isBlank()) {
                imgUrl = trailingSlashTrim(helpHost) + shot.path();
            }
            if (imgUrl == null || imgUrl.isBlank()) {
                continue;
            }
            sb.append("<div style=\"margin:0 0 14px;\">")
                    .append("<img src=\"")
                    .append(escapeAttr(imgUrl))
                    .append("\" alt=\"")
                    .append(escapeAttr(shot.alt() == null ? "Screenshot" : shot.alt()))
                    .append("\" width=\"520\" style=\"display:block;width:100%;max-width:520px;")
                    .append("height:auto;border:1px solid #E8EAE8;border-radius:4px;\"/>");
            if (notBlank(shot.caption())) {
                sb.append("<div style=\"font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;")
                        .append("font-size:12px;line-height:1.5;color:#5C6B63;margin:8px 2px 0;\">")
                        .append(escape(shot.caption().trim()))
                        .append("</div>");
            }
            sb.append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String seeHowHtml(String helpHost, List<Guide> guides) {
        if (guides == null || guides.isEmpty()) {
            return "";
        }
        String host = trailingSlashTrim(helpHost);
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    private static String para(String text) {
        if (!notBlank(text)) {
            return "";
        }
        String withBreaks = escape(text.trim()).replace("\n", "<br>");
        return "<div style=\"font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
                + "font-size:14px;font-weight:400;color:#5C6B63;line-height:1.55;margin:0 0 14px;\">"
                + withBreaks
                + "</div>";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
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
