package zelisline.ub.ai.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Static page knowledge for Guide {@code explain_page}. Facts only — the model must not invent
 * balances or product data beyond this catalog + user message.
 */
@Component
public class RouteGuideCatalog {

    public record RouteGuide(
            String surface,
            String title,
            String summary,
            List<String> tips,
            List<String> suggestions
    ) {}

    public RouteGuide resolve(String route, String surface) {
        String path = normalize(route);
        String surf = surface == null ? "" : surface.trim().toLowerCase(Locale.ROOT);

        if (matches(path, surf, "/business", "business", "hub")) {
            return guide(
                    "business.hub",
                    "Business hub",
                    "Morning pulse for the shop: revenue, profit, stock health, and shortcuts into catalog, suppliers, and analytics.",
                    List.of(
                            "Use the date filters to compare today vs yesterday.",
                            "Stock health cards deep-link into restock and audit.",
                            "AP aging lives under Suppliers — not on this hub."),
                    List.of(
                            "Give me a morning briefing",
                            "What should I check first this morning?",
                            "Why might profit look down?",
                            "Where do I see what shops owe suppliers?"));
        }
        if (matches(path, surf, "/pricing", "pricing", "price")) {
            return guide(
                    "pricing",
                    "Pricing",
                    "Set shelf (sell) prices, cost history, and margin rules. Price Radar combines rule suggest with global catalog recommends.",
                    List.of(
                            "Suggest sell uses latest landed cost × active margin rule.",
                            "Price Radar needs Brain enabled in Super Admin → SokoMind.",
                            "Global catalog recommended sell appears when the item is linked."),
                    List.of(
                            "How does suggest sell work?",
                            "What is Price Radar?",
                            "Where do I set margin rules?"));
        }
        if (matches(path, surf, "/products", "products", "catalog")) {
            return guide(
                    "products.catalog",
                    "Products",
                    "Create and edit SKUs, barcodes, pricing, images, and supplier links. Global catalog can prefill name/image when available.",
                    List.of(
                            "Prefer linking a global catalog product when the barcode matches.",
                            "Sell price suggestions use cost × active margin rule.",
                            "Missing images hurt storefront conversion."),
                    List.of(
                            "How do I add a product from barcode?",
                            "How does sell-price suggest work?",
                            "Can I pull an image from the global catalog?"));
        }
        if (matches(path, surf, "/purchasing/ap-aging", "purchasing.ap", "ap", "aging")) {
            return guide(
                    "purchasing.ap",
                    "AP aging",
                    "Open balances on posted supplier invoices, bucketed by how long they are past due. Shows the total the shop owes suppliers plus any prepayments.",
                    List.of(
                            "Total open AP is what the shop still owes suppliers across all invoices.",
                            "Buckets count days past due — Current means the bill is not late yet.",
                            "Set an as-of date or a supplier ID, then Refresh, to narrow the view.",
                            "Settle bills from the Supplies page (Pay open) or the supplier profile — not on this page."),
                    List.of(
                            "Explain the buckets to me",
                            "Who do I owe money to?",
                            "How do I pay a supplier?",
                            "Draft a polite payment reminder SMS"));
        }
        if (matches(path, surf, "/purchasing/record-payment", "purchasing.pay", "pay", "record-payment")) {
            return guide(
                    "purchasing.pay",
                    "Pay open",
                    "Record supplier payments against open supply bills. This screen opens the Supplies list filtered to unpaid bills.",
                    List.of(
                            "Open bills are supplier receipts that are not fully paid yet.",
                            "Pay settles one bill; Pay all clears every open bill for that supplier.",
                            "Deposit prepays a supplier wallet; the credit applies to later supplies.",
                            "AP aging shows the same balances bucketed by how late they are."),
                    List.of(
                            "How do I pay a supplier?",
                            "What is a deposit / prepayment?",
                            "Draft a polite payment reminder SMS"));
        }
        if (matches(path, surf, "/purchasing/intelligence", "purchasing.intel", "intelligence", "intel")) {
            return guide(
                    "purchasing.intel",
                    "Supplier intelligence",
                    "Spend, margin, and risk across suppliers — who you buy from most, at what margin, and where the exposure sits.",
                    List.of(
                            "Charts run off locked purchase and sale history for the selected branch.",
                            "Use the date range and filters to narrow to a supplier or period.",
                            "Drill out to AP aging for open balances, or Suppliers for the directory."),
                    List.of(
                            "Which supplier gives the best margin?",
                            "How much do I spend per supplier?",
                            "Where do I see open balances?"));
        }
        if (matches(path, surf, "/supplies", "purchasing.supplies", "supplies", "receiving")) {
            return guide(
                    "purchasing.supplies",
                    "Receive supplies",
                    "Record stock deliveries from suppliers — stock rises and a bill is created in one step. Also where you pay open bills.",
                    List.of(
                            "New supply records the delivery: stock goes up and the bill is created together.",
                            "Pay settles a bill; Pay all clears everything open for that supplier.",
                            "Deposit prepays a supplier wallet; the credit applies to later supplies.",
                            "Filter chips (Today, 7 days, 30 days, All, Unpaid) shrink the list."),
                    List.of(
                            "How do I record a delivery?",
                            "How do I pay a bill?",
                            "What is a deposit / prepayment?"));
        }
        if (matches(path, surf, "/suppliers", "suppliers", "ap")) {
            return guide(
                    "suppliers.ap",
                    "Suppliers & AP",
                    "Manage local suppliers, open balances, payments, and Path A/B purchasing history for this shop.",
                    List.of(
                            "Open balance is AP truth from invoices minus allocated payments.",
                            "Invite suppliers to the Supplier Portal from marketplace/portal flows.",
                            "Path B is for quick purchase notes; Path A is PO → GRN → invoice."),
                    List.of(
                            "Draft a polite payment reminder SMS",
                            "How do I record a supplier payment?",
                            "What is Path A vs Path B?",
                            "How do I invite a supplier to the portal?"));
        }
        if (matches(path, surf, "/inventory", "inventory", "stock")) {
            return guide(
                    "inventory.stock",
                    "Inventory",
                    "On-hand stock, valuation, transfers, stock-take, daily audit, and restock recommendations.",
                    List.of(
                            "Daily audit flags feed restock lists for admin approval.",
                            "Stock movements are append-only — fixes go through adjustments.",
                            "Restock can group by supplier for Path A POs."),
                    List.of(
                            "How do I run a daily audit?",
                            "Where are restock recommendations?",
                            "How do transfers between branches work?"));
        }
        if (matches(path, surf, "/analytics", "analytics", "reports")) {
            return guide(
                    "analytics",
                    "Analytics",
                    "Sales, margin, category, and staff performance reports for this tenant.",
                    List.of(
                            "Numbers come from locked sale COGS and stock movements.",
                            "Ask Guide about trends on this page — it will explain using page context, not invent figures."),
                    List.of(
                            "What does margin mean here?",
                            "How do I compare categories?",
                            "Where is staff performance?"));
        }
        if (matches(path, surf, "/marketplace", "marketplace")) {
            return guide(
                    "marketplace",
                    "Marketplace",
                    "Discover and connect with platform marketplace suppliers for catalogue and purchasing.",
                    List.of(
                            "Connecting a marketplace supplier links their catalogue into your shop.",
                            "Supplier portal claim is separate — phone OTP at /supplier-portal/claim."),
                    List.of(
                            "How do I connect a supplier?",
                            "What does connecting change in my catalog?"));
        }
        if (path.startsWith("/supplier-portal") || surf.startsWith("supplier-portal")) {
            return guide(
                    "supplier-portal",
                    "Supplier portal",
                    "Supplier-facing hub: shops owed/paid, catalogue, orders, invoices, statements, and messages.",
                    List.of(
                            "Balances are projections of shop AP — not a second ledger.",
                            "Product edits may require store approval depending on platform settings.",
                            "Statements use invoice + payment allocation truth."),
                    List.of(
                            "How do I respond to an order?",
                            "Where do I update paybill details?",
                            "How do I download a statement?"));
        }
        return guide(
                surfaceOrDefault(surf, "app.general"),
                "Kiosk",
                "You are on a Kiosk back-office screen. Guide can explain this page and suggest next steps. It will not invent balances or invent stock numbers.",
                List.of(
                        "Use the left nav to jump between Products, Suppliers, Inventory, and Analytics.",
                        "Ask a specific question about the screen you are on."),
                List.of(
                        "What can I do on this page?",
                        "Where do I manage products?",
                        "Where do I see supplier balances?"));
    }

    private static RouteGuide guide(
            String surface, String title, String summary, List<String> tips, List<String> suggestions) {
        return new RouteGuide(surface, title, summary, tips, suggestions);
    }

    private static String normalize(String route) {
        if (route == null || route.isBlank()) {
            return "/";
        }
        String path = route.trim();
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String path, String surface, String prefix, String... surfaceTokens) {
        if (path.equals(prefix) || path.startsWith(prefix + "/")) {
            return true;
        }
        for (String token : surfaceTokens) {
            // Exact surface or a dotted descendant (e.g. "suppliers.ap" matches "suppliers").
            // Never substring-contains: "app.general" must not match the "ap" token.
            if (surface.equals(token) || surface.startsWith(token + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String surfaceOrDefault(String surface, String fallback) {
        return surface == null || surface.isBlank() ? fallback : surface;
    }

    public List<String> defaultSuggestions(String route, String surface) {
        return new ArrayList<>(resolve(route, surface).suggestions());
    }
}
