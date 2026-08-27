package zelisline.ub.onboarding.sequence.application;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import zelisline.ub.onboarding.sequence.MerchantOnboardingStep;
import zelisline.ub.platform.email.application.PlatformCampaignEmailRenderer;

/**
 * Renders onboarding sequence messages as mini-guides (email HTML + plain text +
 * chat/in-app summaries). Screenshots: default static /help assets + optional
 * {@code app.onboarding.sequence.shot.<step>} overrides.
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
     * @param innerBodyHtml lesson sections + shots (no shell)
     * @param chatBody support-chat tip body (teaches without the full email length)
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
            String whatsAppBody,
            String chatBody
    ) {
        /** Back-compat for callers that omit chatBody. */
        public RenderedMessage(
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
            this(subject, previewText, plainBody, htmlBody, innerBodyHtml,
                    ctaLabel, ctaPath, inAppTitle, inAppBody, whatsAppBody, inAppBody);
        }
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
            case M2_SIZES -> guide(
                    step,
                    "Coca-Cola 500ml, 1L and a crate — one product or three?",
                    "If it's the same product in different sizes, don't create three unrelated items.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "Shops often create “Coke 500ml”, “Coke 1L”, and “Coke crate” as three separate products. Restock becomes a mess, the till can’t find the right size, and the online shop shows three lonely pages instead of one clean product with size choices.",
                            "Kiosk families and variants let you model one real product with many sizes or packs under it. Stock, cost, and sales stay honest because they share the same family.",
                            """
                                    • Restock from your supplier once — not three times with mismatched names
                                    • Till scan finds the right size every time
                                    • Online shop shows one product page with size choices
                                    • Packages (“sell by crate”) can deplete base units — no phantom inventory
                                    """,
                            List.of(
                                    "Open Products and find (or create) the parent product — the family (e.g. Coca-Cola).",
                                    "Add each size or pack as a variant under that family (500ml, 1L, crate).",
                                    "If you sell crates, set the pack so it depletes the base unit stock when sold.",
                                    "Scan or sell from Cashier — pick the variant, not three unrelated SKUs."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "A dukani selling soda: one Coca-Cola family, variants 500ml / 1L / crate. When a crate sells, base bottles come off stock automatically — you never “lose” inventory to a wrong product name.",
                            "If you already created lookalike products by hand, group them into a family before you take more stock — cleaning later is harder once sales are split across three names.",
                            "Never create a new product for a new size of something you already sell. New size = new variant on the existing family.",
                            "Create or fix a family now — your stock count will thank you."),
                    "Create a family",
                    "/products",
                    "Sizes done right",
                    "Same drink, different sizes? One family with variants — not three products. Open Products and group them so stock stays honest.",
                    null,
                    help,
                    MerchantOnboardingEmailCraft.contrastTableHtml());
            case M3_MONEY_LOOP -> guide(
                    step,
                    "Next: from supplier to till — the money loop",
                    "Stock rises when you post a supply — not when you save an order.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "Having products on the shelf isn’t enough. If you don’t record who you buy from and when stock arrives, you’ll guess at counts, reorder late, and wonder why the till and the store don’t match.",
                            "The money loop is the everyday path every Kenyan shop runs: supplier → delivery (supply) → open shift → sell. Kiosk ties those steps together so stock only moves when goods actually arrive, and sales deplete the same catalog.",
                            """
                                    • You always know who to call / WhatsApp for a reorder
                                    • Stock goes up only when you confirm a delivery — no phantom inventory from “saved orders”
                                    • The till sells the same items you just received
                                    • Reports show cost and margin against real supplies
                                    """,
                            List.of(
                                    "Add a supplier (Suppliers) — name + phone is enough; that phone becomes the WhatsApp number for orders.",
                                    "Link the products you buy from them so ordering is pick-and-tap instead of retyping.",
                                    "When stock arrives, post a supply (Supplies) / confirm delivery — this is what raises on-hand qty.",
                                    "Open a shift, then sell on Cashier — cash or M-Pesa STK — on that same catalog."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "You buy unga from Ruiru Cash & Carry. Add them as a supplier, link 2kg unga, post the delivery when the bags arrive, open a shift, sell. Tomorrow’s reorder is one tap — not a notebook guess.",
                            "Saving a purchase order does NOT increase stock. Only posting the supply / confirming delivery does. If counts look “stuck”, check whether the supply was posted.",
                            "Post supplies the same day stock arrives — even a rough count beats updating “sometime later.”",
                            "Start with your main supplier, then take the first sale on that stock."),
                    "Add your first supplier",
                    "/suppliers",
                    "Suppliers → supply → first sale",
                    "Add a supplier, post a delivery so stock rises, open a shift, sell on Cashier. Stock only moves when goods arrive — not when you save an order.",
                    null,
                    help,
                    null);
            case M4_FIRST_SALE -> guide(
                    step,
                    safeBiz + ": your first sale on Kiosk",
                    "Kazi nzuri — two small habits lock in the till you just proved works.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "A first sale feels great — and it’s also where shops quietly lose control: float left open overnight, cash never counted, M-Pesa still “later.”",
                            "You just proved the catalog and till work. Closing the shift turns today’s cash into an audit trail (float in → sales → count → variance). Connecting M-Pesa lets customers pay by STK so you’re not only counting notes.",
                            """
                                    • Closing count gives a variance you can trust — managers sleep better
                                    • Every cashier shift becomes comparable night to night
                                    • M-Pesa STK cuts change drama and speeds the queue
                                    • Tomorrow’s open float starts from a clean close
                                    """,
                            List.of(
                                    "Open Shifts and close tonight’s shift — enter the closing cash count.",
                                    "Look at the variance (expected vs counted). Small differences happen; big ones deserve a look.",
                                    "If you haven’t yet, open Payments → settings and connect M-Pesa for STK push.",
                                    "Tomorrow: open a new shift with a clear opening float before the first scan."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "After a busy afternoon at " + safeBiz + ", you close with 12,400 counted vs 12,350 expected — a small variance you can note. Without closing, that gap disappears into “we think we had about twelve thousand.”",
                            "You need an open shift to sell. Closing doesn’t delete sales — it locks the period so the next shift starts clean.",
                            "Close every night you sell — even short days. The habit is the product.",
                            "Close tonight’s shift, then connect M-Pesa when you’re ready."),
                    "Close tonight's shift",
                    "/shifts",
                    "First sale",
                    "Kazi nzuri — first sale landed. Close tonight’s shift so float, sales and count add up, and connect M-Pesa when you’re ready for STK.",
                    "🎉 Kazi nzuri, " + safeName + "! First sale on Kiosk. Tonight, close the shift so the float, sales and count all add up. Details in your email. — Kiosk",
                    help,
                    null);
            case M4_FALLBACK -> renderM4Fallback(step, safeName, safeBiz, origin, help, snap);
            case M5_GO_LIVE -> guide(
                    step,
                    safeBiz + ": put your shop online today",
                    "Publish your shop, brand it, share it — same stock count as the till.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "Customers already ask “do you deliver?” and “can I order on WhatsApp?” Without a storefront, every answer is a manual back-and-forth — and you risk promising stock you don’t have.",
                            "Your Kiosk storefront is a real online shop on the same catalog as the till. Publish it, brand it, share the link. Web orders deplete the same stock — no overselling, no second inventory.",
                            """
                                    • One stock count for counter and online
                                    • Customers browse and order without you typing every reply
                                    • Share one link in WhatsApp status — that’s how first web orders arrive
                                    • You fulfil from Web orders with the same products you sell in-store
                                    """,
                            List.of(
                                    "Open Business → Settings (" + origin + "/business/settings) and publish / enable the storefront.",
                                    "Open Business → Design (" + origin + "/business/design) — add logo and colours so it looks like your shop.",
                                    "Copy your shop link (" + origin + ") and post it to WhatsApp status or your group.",
                                    "When an order lands, open Storefront → Web orders and fulfil — stock drops automatically."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "A customer in the estate sees your WhatsApp status with the shop link, orders cooking oil for pickup, pays, and you pack from the same shelf the till uses. No double bookkeeping.",
                            "Publish only when you have sellable stock and a way to receive payment (cash on pickup and/or M-Pesa). An empty published shop trains customers to bounce.",
                            "Your first web order often comes from someone who already buys in person — share the link with them first.",
                            "Publish, brand, then share the link today."),
                    "Open storefront settings",
                    "/business/settings",
                    "Put your shop online",
                    "Publish your storefront — same stock as the till. Brand it, share the link on WhatsApp, fulfil from Web orders.",
                    null,
                    help,
                    null);
            case M6_TEAM -> guide(
                    step,
                    "Run " + safeBiz + " with a team and a rhythm",
                    "Invite staff with PINs — and let Kiosk do the counting while you’re away.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "If only you can open the till, the shop stops when you step out. Shared logins also hide who sold what — and who left the shift open.",
                            "Invite cashiers and managers with their own accounts and till PINs. Pair that with a simple weekly rhythm: post supplies as they arrive, close every shift, glance at what moved.",
                            """
                                    • You can leave the counter without locking the business
                                    • Each PIN shows who rang the sale
                                    • Managers get the right permissions; cashiers stay focused on the till
                                    • Weekly rhythm keeps stock and cash from drifting
                                    """,
                            List.of(
                                    "Open Users and invite a cashier or manager (email / phone as you use today).",
                                    "Set their role and till PIN — one person, one PIN.",
                                    "Walk them through: open shift → sell → close shift.",
                                    "Agree the weekly rhythm: post supplies when stock arrives, close every shift, restock what moved."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "You invite Amina as cashier with her own PIN. While you’re at the supplier, she opens a shift, sells, and closes. You later see her sales — not a mystery “shared” drawer.",
                            "Don’t share one owner login with the whole shop. Shared credentials erase accountability and make support harder when something goes wrong.",
                            "Start with one trusted cashier before you invite the whole team — teach the close-shift habit first.",
                            "Invite your first staff member now."),
                    "Invite staff",
                    "/users",
                    "Team + rhythm",
                    "Invite staff with their own till PINs and run a simple weekly rhythm: post supplies, close shifts, restock what moved.",
                    null,
                    help,
                    null);
            case W_WEEK_CHECKIN -> renderWeek(step, safeName, safeBiz, origin, help, snap);
            case N1_LOOKALIKE -> guide(
                    step,
                    "These look like one family — group them",
                    "Lookalike products on your shelf usually mean sizes of the same thing.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "Kiosk noticed products that look like the same item in different sizes or packs. Left as separate products, stock and sales split — and restock becomes guesswork.",
                            "Group lookalikes into one family with variants so every size shares honest stock and one product page.",
                            "Clean families now prevent a month of messy reports and “which Coke is which?” at the till.",
                            List.of(
                                    "Open Products and find the lookalike names.",
                                    "Pick (or create) the family, then move sizes into variants.",
                                    "Sell from Cashier using the variant — confirm stock moves as one product."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "“Coke 500” and “Coke 1L” sitting as two products → one Coca-Cola family with two variants.",
                            null,
                            "If you’re unsure which is the parent, use the name customers say out loud (“Coca-Cola”) as the family.",
                            "Open Products and group them before the next delivery."),
                    "Open products",
                    "/products",
                    "Sizes done right?",
                    "These look like one family — group them into variants so stock stays honest. Open Products and fix them before the next delivery.",
                    null,
                    help,
                    null);
            case N2_CLOSE_SHIFT -> guide(
                    step,
                    "Remember to close tonight’s shift",
                    "An open shift overnight turns today’s cash into a blur.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "You sold today — great. If the shift stays open, tomorrow’s float and tonight’s cash mix, and you lose the variance story.",
                            "Closing the shift locks float → sales → closing count into one period you can trust.",
                            "Managers and owners get a clean night-to-night picture; cashiers finish with a clear end.",
                            List.of(
                                    "Open Shifts.",
                                    "Close the open shift and enter the closing cash count.",
                                    "Note the variance — ask if it’s larger than pocket change."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "Close before you leave — even if you’ll reopen in the morning.",
                            null,
                            "Closing takes a minute; reconciling a week of open shifts takes an afternoon.",
                            "Close tonight’s shift now."),
                    "Open shifts",
                    "/shifts",
                    "Remember to close tonight",
                    "You sold today — close tonight’s shift so float, sales and count reconcile into a variance you can trust.",
                    null,
                    help,
                    null);
            case N4_WEB_ORDER -> guide(
                    step,
                    "Someone ordered online!",
                    "A real customer used your storefront — fulfil from Web orders.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "A web order is sitting while the customer waits. If you only watch the till, you might miss it.",
                            "Open Storefront → Web orders, fulfil the order, and pack from the same stock the till uses — quantities drop automatically.",
                            "Fast fulfilment turns a first online buyer into a repeat one — and proves the storefront is real.",
                            List.of(
                                    "Open Storefront → Web orders.",
                                    "Open the new order, confirm payment/status, and fulfil / mark ready.",
                                    "Pack from the shelf — stock already moved with the order lines."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "Treat the first web order like a VIP walk-in — reply fast if they messaged too.",
                            null,
                            "Pin Web orders on busy days so the badge doesn’t hide behind other tabs.",
                            "Open Web orders and fulfil it now."),
                    "Open web orders",
                    "/storefront/web-orders",
                    "Someone ordered online!",
                    "A customer ordered on your storefront. Open Web orders, fulfil it — same stock as the till.",
                    "Someone ordered online on Kiosk! Open Web orders to fulfil. — Kiosk",
                    help,
                    null);
            case M0_WELCOME -> guide(
                    step,
                    "Welcome to Kiosk, " + safeName + "!",
                    "Setup, M-Pesa, storefront, custom work — free help from a real human.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "New software usually means guessing where to click and paying for “implementation.” That’s the opposite of how Kiosk should feel.",
                            "You’re on Kiosk with a real human on call for setup, themes, domains, M-Pesa, and custom tweaks — free. This chat and 0714 282 874 are yours.",
                            "You’ll spend time selling, not decoding menus — and when you’re stuck, someone answers.",
                            List.of(
                                    "Reply in Support anytime with what you’re trying to do.",
                                    "Or call / WhatsApp 0714 282 874.",
                                    "Tomorrow we’ll show the fastest way to fill your shelf from products Kenya already knows."),
                            List.of(),
                            safeBiz + " is live — start with products when you’re ready, or ask us to walk the first setup with you.",
                            null,
                            "There are no stupid questions in week one. Ask early.",
                            "Open Support or reply here when you’re ready."),
                    "Open your hub",
                    "/business",
                    "Welcome to Kiosk!",
                    "You’re in. Setup, M-Pesa, storefront, and custom help are free from a real human — reply in Support or call 0714 282 874.",
                    "Karibu Kiosk, " + safeName + "! Reply here anytime — a human answers.",
                    help,
                    null);
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
                base.whatsAppBody(),
                base.chatBody());
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
                ? " Specialty shops: search the Global catalog first for shared brands, then hand-add only the unique SKUs your niche needs."
                : "";

        if (migrating) {
            return guide(
                    step,
                    safeBiz + ": bring your product list into Kiosk",
                    "Import your spreadsheet first — then fill gaps from the Global catalog.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            "You already have products somewhere — a spreadsheet, another POS, a notebook. Retyping every line into a new system wastes a day and introduces typos that break barcodes and stock.",
                            "Import your existing list into Kiosk, then use the Global catalog only for gaps. Same family/variant rules apply after import so sizes don’t splinter.",
                            """
                                    • Hours saved vs typing every SKU
                                    • Barcodes and names come across instead of being reinvented
                                    • Global catalog fills what’s missing with Kenyan-ready defaults
                                    • One catalog feeds till, reports, and online shop
                                    """,
                            List.of(
                                    "Open Business → Import and upload your spreadsheet (or export from your old POS).",
                                    "Review the mapped columns and finish the import.",
                                    "Open Products → Global catalog and pull anything still missing — barcodes included.",
                                    "Clean sizes: same drink, different sizes → one family with variants (not three products)."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "You export 400 lines from the old system, import in one pass, then add 20 missing local SKUs from Global catalog. Afternoon tea, shelf is sellable.",
                            "Only type a product by hand if it’s truly not in your file and not in the shared catalog." + nicheNote,
                            "Import first even if the sheet is messy — cleaning inside Kiosk beats retyping from scratch.",
                            "Import your spreadsheet, then fill gaps from Global catalog."),
                    "Import spreadsheet",
                    "/business/import",
                    "Bring your product list in",
                    "You already have a product list — import it first, then fill gaps from Global catalog. Don’t retype what you already built.",
                    null,
                    help,
                    null);
        }

        return guide(
                step,
                safeBiz + ": fill your shelf in 10 minutes",
                "Thousands of Kenyan-ready items — barcodes included — are one tap away.",
                new MerchantOnboardingEmailCraft.GuideLesson(
                        "Hi " + safeName + ",",
                        "A live till with nothing to sell is just a screen. Typing every soda, unga, and soap by hand takes hours — and you still mistype barcodes.",
                        "The Global catalog is a shared library of products Kenyan shops already stock — names, barcodes, sensible defaults. You pick a starter pack (or search), review, and import. One catalog feeds your till, reports, and online shop.",
                        """
                                • About ten minutes to a sellable shelf for a typical dukani
                                • Barcodes included — scan instead of guessing
                                • Same products work on Cashier and the online store
                                • You only hand-build what’s truly missing
                                """,
                        List.of(
                                "Open Products → Global catalog.",
                                "Pick a starter pack that matches your shop (or search for brands you carry).",
                                "Review what will land on your shelf — remove anything you don’t sell.",
                                "Import. Then open Cashier and confirm an item appears when you search or scan.",
                                "Rule before creating anything new: same product, different sizes → one family with variants — never three unrelated products."),
                        MerchantOnboardingEmailCraft.shotsFor(step),
                        "A neighbourhood dukani imports a grocery starter pack: Coca-Cola, unga, cooking oil, soap. Ten minutes later the till scans a 500ml Coke and the sale posts — no typing.",
                        "Only type a product by hand if you’ve searched the catalog and it’s truly not there." + nicheNote,
                        "Resist “I’ll add products as I go” for week one — an empty shelf trains you to skip the till. Fill a starter set first.",
                        "Open the Global catalog and import your first pack."),
                "Open Global catalog",
                "/products/catalog",
                "Fill your shelf in 10 minutes",
                "Your till is live but empty. Open Global catalog, pick a starter pack, import — barcodes included. Hand-add only what’s truly missing.",
                null,
                help,
                null);
    }

    private RenderedMessage renderM4Fallback(
            MerchantOnboardingStep step,
            String safeName,
            String safeBiz,
            String origin,
            String help,
            MerchantOnboardingGateService.Snapshot snap
    ) {
        long stocked = snap == null ? 0 : snap.sellableSkuCount();
        if (stocked == 0) {
            return guide(
                    step,
                    safeBiz + ": 10 minutes gets you selling",
                    "The shop is still quiet because the shelf is empty — fix that first.",
                    new MerchantOnboardingEmailCraft.GuideLesson(
                            "Hi " + safeName + ",",
                            safeBiz + " can’t take a sale until something is on the shelf. That’s normal in week one — and it’s fixable in about ten minutes.",
                            "Use the Global catalog starter path: pick a pack → review → import. Then open a shift and sell.",
                            "A stocked shelf turns “we should try Kiosk” into a real till day.",
                            List.of(
                                    "Open Products → Global catalog.",
                                    "Pick a starter pack and import.",
                                    "Open a shift and take a small cash or M-Pesa sale on Cashier.",
                                    "Stuck? Call or WhatsApp 0714 282 874 — free human walkthrough."),
                            MerchantOnboardingEmailCraft.shotsFor(step),
                            "Import the grocery pack, sell one 500ml soda to yourself as a test — the loop is real.",
                            null,
                            "Don’t wait for the “perfect” product list. A small starter pack beats an empty ideal catalog.",
                            "Open Global catalog now — or reply here and we’ll do it together."),
                    "Open Global catalog",
                    "/products/catalog",
                    "10 minutes gets you selling",
                    "Shelf still empty — that’s okay. Global catalog → pick a pack → import, then take the first sale. Want to do it together? Reply or call 0714 282 874.",
                    safeName + ", " + safeBiz + " is still empty — and that's okay. 10 minutes fills it: Global catalog → pick a pack → import. Want to do it together? Reply here. — Kiosk",
                    help,
                    null);
        }
        return guide(
                step,
                safeBiz + ": your shelf is ready — open the till",
                "Products are on the shelf. The next step is the first sale.",
                new MerchantOnboardingEmailCraft.GuideLesson(
                        "Hi " + safeName + ",",
                        "Stock without a sale is inventory sitting still. The till is where Kiosk becomes your shop’s daily machine.",
                        "Open a shift with a small opening float, sell on Cashier (cash or M-Pesa STK), then close tonight so the count reconciles.",
                        "First sale proves the catalog, payments, and shift loop — everything after builds on that.",
                        List.of(
                                "Open Shifts and start a shift with a small opening float.",
                                "Open Cashier — sell one real item (even a test to yourself).",
                                "Close the shift tonight with a closing count.",
                                "Want a walkthrough? Call or WhatsApp 0714 282 874 — free."),
                        MerchantOnboardingEmailCraft.shotsFor(MerchantOnboardingStep.M4_FIRST_SALE),
                        "Opening float 2,000, sell three items, close — you now have a variance story for the night.",
                        null,
                        "The first sale can be tiny. Momentum matters more than volume on day three.",
                        "Open Cashier and take the first sale."),
                "Open Cashier",
                "/cashier",
                "Ready for the till?",
                "Shelf is stocked — open a shift, sell on Cashier, close tonight. Stuck? We’ll walk you through free: 0714 282 874.",
                safeName + ", " + safeBiz + " is stocked but the till is still quiet. Open a shift and take the first sale — or reply here and we'll walk you through it. — Kiosk",
                help,
                null);
    }

    private RenderedMessage renderWeek(
            MerchantOnboardingStep step,
            String safeName,
            String safeBiz,
            String origin,
            String help,
            MerchantOnboardingGateService.Snapshot snap
    ) {
        long products = snap == null ? 0 : snap.sellableSkuCount();
        long suppliers = snap == null ? 0 : snap.supplierCount();
        long sales = snap == null ? 0 : snap.saleCount();
        String thanks = sales > 0
                ? "Asante — one week in, here's what you've built"
                : "One week in, here's what you've built";
        String waThanks = sales > 0 ? "Asante, " + safeName : "Week 1 done, " + safeName;

        return guide(
                step,
                "Week 1 on Kiosk: " + products + " products, " + sales + " sales",
                "Here's what " + safeBiz + " built this week — and what to do next.",
                new MerchantOnboardingEmailCraft.GuideLesson(
                        "Hi " + safeName + ",",
                        "Week one without a pause turns into “we installed something” instead of “we run the shop on it.” A short look at the numbers keeps the loop turning.",
                        thanks + ": " + products + " products on the shelf, " + suppliers + " supplier"
                                + (suppliers == 1 ? "" : "s") + ", " + sales + " sales at the till.",
                        """
                                • You see what actually moved — restock with evidence
                                • Gaps (no supplier, no close, no online) become clear next steps
                                • Free human help is still here if a step stalled
                                """,
                        List.of(
                                "Open your business hub and skim products / sales for the week.",
                                "Restock what moved — post a supply if deliveries arrived.",
                                "If you skipped storefront, team, or M-Pesa, pick one and finish it this week.",
                                "Call or WhatsApp 0714 282 874 anytime — free help, real humans."),
                        MerchantOnboardingEmailCraft.shotsFor(step),
                        sales > 0
                                ? "You sold this week — restock the top movers before the weekend rush."
                                : "If sales are still zero, reopen the catalog or till path — don’t wait for a perfect setup.",
                        null,
                        "Pick one unfinished step this week rather than three half-done ones.",
                        "Open your hub, then restock what moved."),
                "See your business hub",
                "/business",
                "Your week 1",
                products + " products · " + sales + " sales this week. Restock what moved, finish one skipped step, ask if you’re stuck.",
                waThanks + ": " + products + " products, " + sales
                        + " sales. Next: keep restocking what moved. Reply here for help anytime. — Kiosk",
                help,
                null);
    }

    private RenderedMessage guide(
            MerchantOnboardingStep step,
            String subject,
            String preview,
            MerchantOnboardingEmailCraft.GuideLesson lesson,
            String ctaLabel,
            String ctaPath,
            String inAppTitle,
            String inAppBody,
            String whatsAppBody,
            String helpHost,
            String middleHtml
    ) {
        List<MerchantOnboardingEmailCraft.Guide> guides = MerchantOnboardingEmailCraft.guidesFor(step);
        String override = shotOverride(step);
        String plain = MerchantOnboardingEmailCraft.renderGuidePlain(lesson, helpHost, guides);
        String inner = MerchantOnboardingEmailCraft.renderGuideHtml(
                lesson, helpHost, guides, override, middleHtml);
        String chat = MerchantOnboardingEmailCraft.renderGuideChat(lesson, ctaPath);
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
                whatsAppBody,
                chat);
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
