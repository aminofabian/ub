package zelisline.ub.onboarding.sequence.application;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import zelisline.ub.onboarding.sequence.MerchantOnboardingStep;
import zelisline.ub.platform.email.application.PlatformCampaignEmailRenderer;

/**
 * Renders onboarding sequence emails (HTML + plain text) from the creative brief.
 * Screenshots: default static /help assets + optional {@code app.onboarding.sequence.shot.<step>}
 * overrides; lessons link out to annotated help articles ("See how").
 */
@Component
public class MerchantOnboardingMessageRenderer {

    private final PlatformCampaignEmailRenderer campaignEmailRenderer;
    private final Environment environment;

    @Value("${app.public.host-url:https://kiosk.ke}")
    private String hostUrl;

    public MerchantOnboardingMessageRenderer(
            PlatformCampaignEmailRenderer campaignEmailRenderer,
            Environment environment
    ) {
        this.campaignEmailRenderer = campaignEmailRenderer;
        this.environment = environment;
    }

    /**
     * @param htmlBody full Kiosk email shell
     * @param innerBodyHtml lesson + optional shot + See-how (no shell) — use when rebuilding CTA URL
     */
    public record RenderedMessage(
            String subject,
            String previewText,
            String plainBody,
            String htmlBody,
            String innerBodyHtml,
            String ctaLabel,
            String ctaPath,
            String inAppTitle,
            String inAppBody,
            String whatsAppBody
    ) {
    }

    public RenderedMessage render(
            MerchantOnboardingStep step,
            String name,
            String businessName,
            String shopUrl,
            MerchantOnboardingGateService.Snapshot snap
    ) {
        return render(step, name, businessName, shopUrl, snap, null);
    }

    public RenderedMessage render(
            MerchantOnboardingStep step,
            String name,
            String businessName,
            String shopUrl,
            MerchantOnboardingGateService.Snapshot snap,
            String muteUrl
    ) {
        String safeName = blankTo(name, "there");
        String safeBiz = blankTo(businessName, "your shop");
        String origin = trailingSlashTrim(shopUrl);
        String help = trailingSlashTrim(hostUrl);

        RenderedMessage base = switch (step) {
            case M1_FILL_SHELF -> renderM1(step, safeName, safeBiz, origin, help, snap);
            case M2_SIZES -> message(
                    step,
                    "Coca-Cola 500ml, 1L and a crate — one product or three?",
                    "If it's the same product in different sizes, don't create three unrelated items.",
                    """
                            Hi %s,

                            If it's the same product in different sizes or packs, don't create three unrelated items. Create one family, then variants. Stock, cost, and sales stay honest.

                            Wrong: three separate products — "Coke 500ml", "Coke 1L", "Coke crate".
                            Right: family Coca-Cola → variants 500ml, 1L → optional crate/pack that depletes base units.

                            Why shops care:
                            - Restock from your supplier once, not three times with mismatched names
                            - Till scan finds the right size
                            - Online shop shows one clean product page with size choices
                            - Packages ("sell by crate") share base stock — no phantom inventory

                            Create a family: %s/products
                            """.formatted(safeName, origin),
                    "Create a family",
                    "/products",
                    "Sizes done right",
                    "Same drink, different sizes? One family, not three products.",
                    null,
                    help,
                    MerchantOnboardingEmailCraft.contrastTableHtml());
            case M3_MONEY_LOOP -> message(
                    step,
                    "Next: from supplier to till — the money loop",
                    "Stock rises when you post a supply — not when you save an order.",
                    """
                            Hi %s,

                            You have a shelf. Now let's build the loop that runs every shop: buy → receive → sell.

                            1. Add a supplier — your real buying contact. Phone is enough; it becomes the WhatsApp number for orders.
                            2. Link what you buy from them — so ordering is pick-and-tap instead of retyping.
                            3. Post a delivery (a supply) when stock arrives.

                            Critical: saving an order does NOT increase stock. Posting the supply / confirming delivery does.

                            4. Open a shift, then sell — cash or M-Pesa STK, on the same catalog you just stocked.

                            Add your first supplier: %s/suppliers
                            Record a delivery: %s/supplies
                            Open Cashier: %s/cashier
                            """.formatted(safeName, origin, origin, origin),
                    "Add your first supplier",
                    "/suppliers",
                    "Suppliers → supply → first sale",
                    "Add a supplier, post a delivery so stock rises, open a shift, sell on Cashier.",
                    null,
                    help);
            case M4_FIRST_SALE -> message(
                    step,
                    safeBiz + ": your first sale on Kiosk 🎉",
                    "Kazi nzuri! Two small habits to build tonight.",
                    """
                            Hi %s,

                            Kazi nzuri — %s just made its first sale on Kiosk.

                            Two habits from here, and they're both small:
                            1. Close your shift tonight. Float in, count the till, look at the variance — that's the audit trail your business runs on.
                            2. Connect M-Pesa if you haven't yet, so customers pay by STK push and money lands without counting notes.

                            Close tonight's shift: %s/shifts
                            Connect M-Pesa: %s/payments/settings

                            And when the next shift opens, you're running a real till.
                            """.formatted(safeName, safeBiz, origin, origin),
                    "Close tonight's shift",
                    "/shifts",
                    "First sale 🎉",
                    "Close tonight's shift, connect M-Pesa when ready.",
                    "🎉 Kazi nzuri, " + safeName + "! First sale on Kiosk. Tonight, close the shift so the float, sales and count all add up. Details in your email. — Kiosk",
                    help);
            case M4_FALLBACK -> {
                // Gate is "no sale by day 3"; the copy must match the state:
                // empty shelf → replay the 10-minute catalog path; stocked but
                // quiet → push the first sale, not "fill the shelf".
                long stocked = snap == null ? 0 : snap.sellableSkuCount();
                if (stocked == 0) {
                    yield message(
                            step,
                            safeBiz + ": 10 minutes gets you selling",
                            "Your shelf can be full tonight — Global catalog → pick a pack → import.",
                            """
                                    Hi %s,

                                    %s is still quiet — and that's okay. Ten minutes fills it:
                                    1. Open the Global catalog
                                    2. Pick a starter pack
                                    3. Import

                                    Want to do it together? Call or WhatsApp 0714 282 874 — a real human will walk you through. Free help.

                                    Open Global catalog: %s/products/catalog
                                    """.formatted(safeName, safeBiz, origin),
                            "Open Global catalog",
                            "/products/catalog",
                            "10 minutes gets you selling",
                            "Your shelf is still empty. Start from Global catalog — or reply for free human help.",
                            safeName + ", " + safeBiz + " is still empty — and that's okay. 10 minutes fills it: Global catalog → pick a pack → import. Want to do it together? Reply here. — Kiosk",
                            help);
                }
                yield message(
                        step,
                        safeBiz + ": your shelf is ready — open the till",
                        "Your products are on the shelf. The next step is the first sale.",
                        """
                                Hi %s,

                                %s has a stocked shelf now. The next step is the first sale.

                                1. Open a shift with a small opening float.
                                2. Sell on Cashier — cash or M-Pesa STK, on the same catalog you stocked.
                                3. Close the shift tonight; the closing count gives a variance you can trust.

                                Open Cashier: %s/cashier
                                Open a shift: %s/shifts

                                Want to do it together? Call or WhatsApp 0714 282 874 — a real human will walk you through. Free help.
                                """.formatted(safeName, safeBiz, origin, origin),
                        "Open Cashier",
                        "/cashier",
                        "Ready for the till?",
                        "Your shelf is stocked — open a shift and take the first sale. Stuck? We'll help free.",
                        safeName + ", " + safeBiz + " is stocked but the till is still quiet. Open a shift and take the first sale — or reply here and we'll walk you through it. — Kiosk",
                        help);
            }
            case M5_GO_LIVE -> message(
                    step,
                    safeBiz + ": put your shop online today",
                    "Publish your shop, brand it, share it — same stock count as the till.",
                    """
                            Hi %s,

                            Your shop has a web address ready: %s. Publish it and customers can browse, order and pay — and every web order draws from the same stock count as your till. No overselling, no double counting.

                            1. Publish the storefront.
                            2. Brand it — logo and colours in a couple of taps.
                            3. Share the link in your WhatsApp status — that's how your first web orders arrive.

                            Open storefront settings: %s/business/settings
                            Brand it: %s/business/design
                            """.formatted(safeName, origin, origin, origin),
                    "Open storefront settings",
                    "/business/settings",
                    "Put your shop online",
                    "Publish your storefront — same stock count as the till.",
                    null,
                    help);
            case M6_TEAM -> message(
                    step,
                    "Run " + safeBiz + " with a team and a rhythm",
                    "Invite staff with PINs — and let Kiosk do the counting.",
                    """
                            Hi %s,

                            You built the machine. Now let it run without you.

                            1. Invite staff — cashiers and managers, each with their own till PIN.
                            2. Learn the weekly rhythm — post supplies as they arrive, run a stock take weekly, close every shift, watch the numbers.
                            3. Still stuck on anything? Free human help, always.

                            Invite staff: %s/users
                            """.formatted(safeName, origin),
                    "Invite staff",
                    "/users",
                    "Team + rhythm",
                    "Invite staff with PINs and let Kiosk run the weekly rhythm.",
                    null,
                    help);
            case W_WEEK_CHECKIN -> {
                long products = snap == null ? 0 : snap.sellableSkuCount();
                long suppliers = snap == null ? 0 : snap.supplierCount();
                long sales = snap == null ? 0 : snap.saleCount();
                yield message(
                        step,
                        "Week 1 on Kiosk: " + products + " products, " + sales + " sales",
                        "Here's what " + safeBiz + " built this week.",
                        """
                                Hi %s,

                                One week in, here's what you've built: %d products on the shelf, %d supplier%s, %d sales at the till.

                                This week: keep the loop turning — restock what moved, try the online shop, and if there's a step you skipped, we'll walk it with you.

                                See your business hub: %s/business
                                Call or WhatsApp 0714 282 874 anytime — free help, real humans.
                                """.formatted(
                                safeName,
                                products,
                                suppliers,
                                suppliers == 1 ? "" : "s",
                                sales,
                                origin),
                        "See your business hub",
                        "/business",
                        "Your week 1",
                        products + " products · " + sales + " sales — see what’s next.",
                        "Week 1 done, " + safeName + ": " + products + " products, " + sales
                                + " sales. Next: keep restocking what moved. Reply here for help anytime. — Kiosk",
                        help);
            }
            case N1_LOOKALIKE -> message(
                    step,
                    "Sizes done right?",
                    "These look like one family.",
                    "These look like one family — group them so stock stays honest.",
                    "Open products",
                    "/products",
                    "Sizes done right?",
                    "These look like one family — group them so stock stays honest.",
                    null,
                    help);
            case N2_CLOSE_SHIFT -> message(
                    step,
                    "Close tonight’s shift",
                    "Closing count → variance you can trust.",
                    "Remember to close tonight’s shift — closing count → variance you can trust.",
                    "Open shifts",
                    "/shifts",
                    "Remember to close tonight",
                    "Nice — remember to close it tonight. Closing count → variance you can trust.",
                    null,
                    help);
            case N4_WEB_ORDER -> message(
                    step,
                    "Someone ordered online!",
                    "Fulfil it from Web orders.",
                    "Someone ordered online! Fulfil it from Web orders — same stock as the till.",
                    "Open web orders",
                    "/storefront/web-orders",
                    "Someone ordered online!",
                    "Fulfil it from Web orders — same stock as the till.",
                    "Someone ordered online on Kiosk! Open Web orders to fulfil. — Kiosk",
                    help);
            case M0_WELCOME -> message(
                    step,
                    "Welcome to Kiosk, " + safeName + "! 🎉",
                    "Setup, M-Pesa, storefront, custom work — all free help from a real human.",
                    "Welcome — already sent at signup.",
                    "Open your hub",
                    "/business",
                    "Welcome to Kiosk!",
                    "Your store is ready. Reach us anytime for free setup help.",
                    "Karibu Kiosk, " + safeName + "! Reply here anytime — a human answers.",
                    help);
        };

        if (muteUrl == null || muteUrl.isBlank()) {
            return base;
        }
        String mutedPlain = base.plainBody() + "\n\nMute these tips: " + muteUrl;
        String mutedInner = base.innerBodyHtml()
                + toHtmlParagraphs("Mute these tips: " + muteUrl);
        return new RenderedMessage(
                base.subject(),
                base.previewText(),
                mutedPlain,
                campaignEmailRenderer.renderHtml(
                        base.subject(),
                        mutedInner,
                        base.ctaLabel(),
                        base.ctaPath(),
                        help,
                        base.previewText()),
                mutedInner,
                base.ctaLabel(),
                base.ctaPath(),
                base.inAppTitle(),
                base.inAppBody(),
                base.whatsAppBody());
    }

    private RenderedMessage renderM1(
            MerchantOnboardingStep step,
            String safeName,
            String safeBiz,
            String origin,
            String help,
            MerchantOnboardingGateService.Snapshot snap
    ) {
        boolean migrating = snap != null && snap.migrating();
        boolean niche = snap != null && snap.nicheSpecialty();
        String nicheNote = niche
                ? "\n\nSpecialty shops: search the Global catalog first for shared brands, then hand-add only the unique SKUs your niche needs.\n"
                : "\n";
        if (migrating) {
            return message(
                    step,
                    safeBiz + ": bring your product list into Kiosk",
                    "Import your spreadsheet first — then fill gaps from the Global catalog.",
                    """
                            Hi %s,

                            You already have a product list — great. Don't retype it.

                            1. Import your spreadsheet (or export from your old POS) at %s/business/import
                            2. After import, open the Global catalog and pull anything that's missing — barcodes and defaults included
                            3. Clean up sizes: same drink, different sizes → one family with variants (not three separate products)

                            Only type a product by hand if it's truly not in your file and not in the shared catalog.
                            %s
                            Import spreadsheet: %s/business/import
                            Open Global catalog: %s/products/catalog

                            Stuck? Call or WhatsApp 0714 282 874 — a real human, free help.
                            """.formatted(safeName, origin, nicheNote, origin, origin),
                    "Import spreadsheet",
                    "/business/import",
                    "Bring your product list in",
                    "Import your spreadsheet first, then fill gaps from Global catalog.",
                    null,
                    help);
        }
        return message(
                step,
                safeBiz + ": fill your shelf in 10 minutes",
                "Thousands of Kenyan-ready items — barcodes included — are one tap away.",
                """
                        Hi %s,

                        Your till is live and waiting. Right now it has nothing to sell — let's fix that in about ten minutes.

                        Open the Global catalog and you'll find thousands of items Kenyan shops already stock — sodas, unga, sugar, cooking oil — with barcodes and sensible defaults already filled in.

                        1. Pick a starter pack that matches your shop.
                        2. Review what will land on your shelf.
                        3. Import. Done — one catalog feeds your till, your reports, and your online shop.

                        Only type a product by hand if you've searched the catalog and it's truly not there.

                        One rule before you create anything new: same product in different sizes? Make it one family (Coca-Cola) with variants (500ml, 1L) — never three unrelated products. Your stock will thank you.
                        %s
                        Open the Global catalog: %s/products/catalog
                        Add products myself: %s/products

                        Stuck? Call or WhatsApp 0714 282 874 — a real human, free help.
                        """.formatted(safeName, nicheNote, origin, origin),
                "Open Global catalog",
                "/products/catalog",
                "Fill your shelf in 10 minutes",
                "Start from Global catalog. Use families for sizes. Add manually only if it's not in the shared catalog.",
                null,
                help);
    }

    private RenderedMessage message(
            MerchantOnboardingStep step,
            String subject,
            String preview,
            String lessonPlain,
            String ctaLabel,
            String ctaPath,
            String inAppTitle,
            String inAppBody,
            String whatsAppBody,
            String helpHost
    ) {
        return message(
                step, subject, preview, lessonPlain, ctaLabel, ctaPath,
                inAppTitle, inAppBody, whatsAppBody, helpHost, null);
    }

    private RenderedMessage message(
            MerchantOnboardingStep step,
            String subject,
            String preview,
            String lessonPlain,
            String ctaLabel,
            String ctaPath,
            String inAppTitle,
            String inAppBody,
            String whatsAppBody,
            String helpHost,
            String middleHtml
    ) {
        List<MerchantOnboardingEmailCraft.Guide> guides = MerchantOnboardingEmailCraft.guidesFor(step);
        MerchantOnboardingEmailCraft.Shot shot = MerchantOnboardingEmailCraft.defaultShot(step);
        String override = shotOverride(step);
        String plain = MerchantOnboardingEmailCraft.appendGuidesToPlain(lessonPlain, helpHost, guides);
        String inner = MerchantOnboardingEmailCraft.innerHtml(
                lessonPlain, helpHost, guides, shot, override, middleHtml);
        String html = campaignEmailRenderer.renderHtml(
                subject,
                inner,
                ctaLabel,
                ctaPath,
                helpHost,
                preview);
        return new RenderedMessage(
                subject,
                preview,
                plain,
                html,
                inner,
                ctaLabel,
                ctaPath,
                inAppTitle,
                inAppBody,
                whatsAppBody);
    }

    private String shotOverride(MerchantOnboardingStep step) {
        if (environment == null || step == null) {
            return null;
        }
        String byKey = environment.getProperty("app.onboarding.sequence.shot." + step.key().toLowerCase(Locale.ROOT));
        if (byKey != null && !byKey.isBlank()) {
            return byKey.trim();
        }
        String byName = environment.getProperty(
                "app.onboarding.sequence.shot." + step.name().toLowerCase(Locale.ROOT));
        return byName == null || byName.isBlank() ? null : byName.trim();
    }

    static String toHtmlParagraphs(String plain) {
        String escaped = escape(plain == null ? "" : plain);
        String[] parts = escaped.split("\\n\\n+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String withBreaks = part.replace("\n", "<br>");
            sb.append("<div style=\"font-family:'DM Sans','Segoe UI',Roboto,Helvetica,Arial,sans-serif;")
                    .append("font-size:14px;font-weight:400;color:#5C6B63;line-height:1.55;margin:0 0 14px;\">")
                    .append(withBreaks)
                    .append("</div>");
        }
        return sb.toString();
    }

    static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String blankTo(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim();
    }

    private static String trailingSlashTrim(String url) {
        if (url == null || url.isBlank()) {
            return "https://kiosk.ke";
        }
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
