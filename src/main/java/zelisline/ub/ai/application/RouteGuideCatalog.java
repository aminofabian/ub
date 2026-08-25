package zelisline.ub.ai.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Static page knowledge for Guide {@code explain_page}. Facts only — the model must not invent
 * balances, product data, or UI beyond this catalog + user message. Facts are audited against the
 * frontend UI (labels, sections, buttons) so the model never describes features that do not exist.
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

        if (matches(path, surf, "/business/settings", "business.settings", "settings")) {
            return guide(
                    "business.settings",
                    "Business settings",
                    "Shop profile plus online storefront: enable the public shop, WhatsApp ordering, catalog branch, and delivery areas.",
                    List.of(
                            "Enable storefront turns on the public catalog and pickup flow.",
                            "WhatsApp orders send the cart straight to your number — no payment gateway needed.",
                            "Catalog branch sets which branch's stock and prices shoppers see.",
                            "Themes and landing templates live under Business → Design and Themes."),
                    List.of(
                            "How do I turn on the online store?",
                            "How do I set up WhatsApp ordering?",
                            "Which branch powers the public catalog?",
                            "Where do I set delivery areas?"));
        }
        if (matches(path, surf, "/business", "business", "hub")) {
            return guide(
                    "business.hub",
                    "Business hub",
                    "Morning pulse for the shop: revenue, profit, open shifts, stock health, and today's supply bills, credit tabs, and web orders — with shortcuts into the rest of the app.",
                    List.of(
                            "Week and Today toggle the view; figures compare against the prior period.",
                            "Pulse cards show Orders, Gross profit, Avg ticket, and Open shifts.",
                            "Needs attention lists open shifts, low stock, expiring batches, open web orders, and payables.",
                            "Store & stock cards deep-link to restock, supply batches, valuation, and AP aging.",
                            "Jump in shortcuts: Sales, Catalogue, Inventory, Analytics, Web orders, On tab, Team."),
                    List.of(
                            "Give me a morning briefing",
                            "What should I check first this morning?",
                            "What needs attention right now?",
                            "Why is gross profit down?"));
        }
        if (matches(path, surf, "/purchasing/ap-aging", "purchasing.ap", "ap", "aging")) {
            return guide(
                    "purchasing.ap",
                    "AP aging",
                    "Open balances on posted supplier invoices, bucketed by how long they are past due. Shows the total the shop owes suppliers plus any prepayments.",
                    List.of(
                            "Total open AP is what the shop still owes suppliers across all invoices.",
                            "Buckets count days past due — Current means the bill is not late yet.",
                            "As-of is optional (leave empty for today UTC); a supplier ID narrows the set.",
                            "Quick links jump to Intelligence, Pay open (unpaid supplies), and Suppliers.",
                            "Bills are settled on the Supplies page (Pay open) or the supplier profile — not on this page."),
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
                    "Record supplier payments against open supply bills. This route opens the Supplies list filtered to unpaid bills.",
                    List.of(
                            "This route redirects to the Supplies list filtered to unpaid bills — it has no page of its own.",
                            "Pay settles one bill; Pay all clears every open bill for that supplier.",
                            "Mark paid · no SMS settles a bill without sending a message.",
                            "Deposit prepays a supplier wallet; the credit applies to later supplies.",
                            "AP aging shows the same balances bucketed by how late they are."),
                    List.of(
                            "How do I pay a supplier?",
                            "How do I mark a bill paid without sending an SMS?",
                            "What is a deposit / prepayment?",
                            "Draft a polite payment reminder SMS"));
        }
        if (matches(path, surf, "/purchasing/intelligence", "purchasing.intel", "intelligence", "intel")) {
            return guide(
                    "purchasing.intel",
                    "Supplier intelligence",
                    "Spend, price competitiveness, and supplier risk — total spend, price variance versus primary cost, and single-source items.",
                    List.of(
                            "Quick range presets or From/To dates filter the period; empty uses the last 90 days.",
                            "Price Variance Alerts flag lines paid above or below the primary cost.",
                            "Single-Source Risk lists items with only one supplier.",
                            "Drill out to AP aging for open balances, or Suppliers for the directory."),
                    List.of(
                            "Which supplier do I buy from most?",
                            "Where am I paying above the primary cost?",
                            "Which items have a single supplier?",
                            "Where do I see open balances?"));
        }
        if (matches(path, surf, "/supplies", "purchasing.supplies", "supplies", "receiving")) {
            return guide(
                    "purchasing.supplies",
                    "Receive supplies",
                    "Record stock deliveries from suppliers — stock rises and a bill is created in one step. Also where you pay open bills.",
                    List.of(
                            "New supply records the delivery: stock goes up and the bill is created together.",
                            "Filter by status (All, Unpaid, Paid) and period (Today, Yesterday, 3 days, 1 week, 2 weeks, Month).",
                            "Pay settles one bill; Pay all clears every unpaid bill for that supplier.",
                            "Deposit to supplier wallet prepays; the credit applies automatically on the next supply.",
                            "Edit supply changes quantities, prices, and extra costs until the bill is paid."),
                    List.of(
                            "How do I record a delivery?",
                            "How do I pay a bill?",
                            "What is a deposit / prepayment?",
                            "How do I edit a supply bill?"));
        }
        if (matches(path, surf, "/order", "ordering", "order")) {
            return guide(
                    "ordering",
                    "Ordering",
                    "Stock-aware ordering from suppliers: build a cart from linked products, send it on WhatsApp, and confirm arrivals as a supply bill.",
                    List.of(
                            "Pick a supplier, then add linked products to the cart.",
                            "Tiles flag items at or below their reorder level.",
                            "Save & WhatsApp posts a Path A purchase order and opens WhatsApp; Save only keeps the draft.",
                            "Round to 10 adjusts the order total to the nearest 10.",
                            "Confirm orders when goods arrive; the arrivals post as a supply bill with stock."),
                    List.of(
                            "How do I place an order with a supplier?",
                            "How do I confirm an order when goods arrive?",
                            "What does Round to 10 do?",
                            "How do I reorder low-stock items?"));
        }
        if (matches(path, surf, "/inventory/stock-take", "inventory.stocktake", "stock-take", "stocktake")) {
            return guide(
                    "inventory.stocktake",
                    "Stock take & daily audit",
                    "Physical stock counting: morning and evening sessions, daily audit, approvals that update stock, and restock review.",
                    List.of(
                            "Stock take starts a Morning or Evening session; count items against the checklist.",
                            "Daily audit samples yesterday's sales into AM and PM counting windows.",
                            "Audit review compares Morning, Evening, and System counts; approving updates stock to the count.",
                            "Escalated items go to Investigations for follow-up.",
                            "Approved restock suggestions generate supplier orders, optionally as Path A purchase orders."),
                    List.of(
                            "How do I run the daily audit?",
                            "What happens when I approve a count in audit review?",
                            "What is the difference between the AM and PM counts?",
                            "Where do approved restock suggestions go?"));
        }
        if (matches(path, surf, "/inventory/restock-digest", "inventory.restockdigest", "restock-digest", "tonights-list")) {
            return guide(
                    "inventory.restockdigest",
                    "Tonight's list",
                    "The nightly restock list: suggested quantities per product with on-hand and par, grouped by supplier so you can turn them into orders.",
                    List.of(
                            "Lines show On hand, Par, editable Qty, and a reason chip — Below min, Will stock out, Fast mover, Recovering stock-out.",
                            "Suppliers group into Order (drafts a Path A purchase order), Pad (adds to the order pad), or Already accepted.",
                            "Accept all or Accept aisle approves the pending lines at once.",
                            "Snooze defers a line by a day; dismiss removes it from the list.",
                            "PDF exports the list per department or supplier.",
                            "The prep view turns the accepted list into a check-off packing sheet."),
                    List.of(
                            "What is tonight's list?",
                            "How do I turn suggestions into a purchase order?",
                            "What does snooze do?",
                            "Where does the prep list live?"));
        }
        if (matches(path, surf, "/inventory", "inventory", "stock")) {
            return guide(
                    "inventory.stock",
                    "Inventory",
                    "Stock levels, restock, supply batches, transfers, and valuation for the selected branch.",
                    List.of(
                            "Stock lists on-hand per branch, flagged Low or Out against a reorder level.",
                            "Edit a quantity inline; increasing stock asks for the unit cost.",
                            "Restock lists zero-on-hand products — enter qty and cost, then Save posts stock.",
                            "Supply batches are deliveries and cost layers; a batch shows cost, revenue, expiry, and waste.",
                            "Transfers move stock between branches — create a draft, then complete it.",
                            "Valuation is quantity remaining × unit cost per active batch."),
                    List.of(
                            "What do Low and Out mean on Stock?",
                            "Which products are out of stock at this branch?",
                            "How do I move stock between branches?",
                            "What is a supply batch and why does expiry matter?"));
        }
        if (matches(path, surf, "/pricing", "pricing", "price")) {
            return guide(
                    "pricing",
                    "Pricing",
                    "Set effective-dated sell prices (optionally per branch), margin rules, and tax rates. Suggest price computes from landed cost; Price Radar adds global catalog recommends.",
                    List.of(
                            "Set selling price creates an effective-dated price row, optionally per branch.",
                            "Suggest price computes sell from latest landed cost × active margin rule.",
                            "Price Radar needs Brain enabled in Super Admin → SokoMind.",
                            "Rules & tax manages margin rules and tax rates (inclusive flag).",
                            "Suggestion inputs are raw IDs — paste the item UUID."),
                    List.of(
                            "How does suggest sell work?",
                            "What is Price Radar?",
                            "Where do I set margin rules?",
                            "How do I set a price for one branch only?"));
        }
        if (matches(path, surf, "/products", "products", "catalog")) {
            return guide(
                    "products.catalog",
                    "Products",
                    "Add and edit products, barcodes, prices, packages, and supplier links; import in bulk; pull items from the shared catalog.",
                    List.of(
                            "Add Product toggles Single product or Group — a group is a family of variants, never sold itself.",
                            "Variants inherit the group's category; each keeps its own barcode, price, and stock.",
                            "Typing a name or a 6+ digit barcode suggests matches from your catalog and the Shared catalog.",
                            "Selecting rows enables bulk actions: Mark active, Adjust stock, Change department, Delete.",
                            "Import offers a CSV Template and Upload CSV; add departments first if you cannot create products.",
                            "Library opens the shared catalog: starter packs and Review & import."),
                    List.of(
                            "What is the difference between a single product and a group?",
                            "How do I add a product from barcode?",
                            "How do I add sizes or variants to a product?",
                            "How do I import products from a CSV?"));
        }
        if (matches(path, surf, "/item-types", "departments", "item-types")) {
            return guide(
                    "departments",
                    "Departments",
                    "Group products by how you run the shop (Grocery, Fruits, Retail). Every product must belong to a department.",
                    List.of(
                            "Add department opens suggestion chips plus your own names.",
                            "Each department has a short code, label, icon, color, and sort order.",
                            "Icons show on storefront type filters.",
                            "Products can be re-departmented in bulk from the Products page."),
                    List.of(
                            "What is a department vs a category?",
                            "Why can't I add a product yet?",
                            "How do I change a product's department?"));
        }
        if (matches(path, surf, "/categories", "categories", "category")) {
            return guide(
                    "categories",
                    "Categories",
                    "Category tree with covers and icons, plus commercial defaults: default markup, tax rate, and linked price rules.",
                    List.of(
                            "Categories form a tree with expand/collapse and inline editing.",
                            "New category creates several at once from suggested names and a shared parent.",
                            "Default markup % and default tax rate apply to new products in the category.",
                            "Price rules can be linked to a category with a precedence."),
                    List.of(
                            "What is the difference between a category and a department?",
                            "How do default markup and tax rates apply to new products?",
                            "How do I link a price rule to a category?",
                            "How do I create several categories at once?"));
        }
        if (matches(path, surf, "/suppliers", "suppliers", "ap")) {
            return guide(
                    "suppliers.ap",
                    "Suppliers & AP",
                    "Manage suppliers, wallet credit, purchases, and payments. Search the directory, open a profile to see what you owe, and pay it.",
                    List.of(
                            "Directory lists Name, Code, Status with search and All / Active / Inactive / Blocked filters.",
                            "Invite portal sends the supplier an SMS claim code to join the Supplier Portal.",
                            "Wallet credit holds deposits; the credit applies automatically on the next supply.",
                            "Purchase history shows total spent, paid, open balance, and each invoice's paid status.",
                            "New supply records a delivery and its bill together; Path A is PO → GRN → invoice."),
                    List.of(
                            "How do I add a supplier?",
                            "How do I invite a supplier to the portal?",
                            "What is the difference between Path A and Path B?",
                            "How do I record a payment or deposit?"));
        }
        if (matches(path, surf, "/customers", "customers", "customer")) {
            return guide(
                    "customers",
                    "Customers",
                    "Directory of customers with phones, owed balances, and wallet, plus credit statements and payment reminders.",
                    List.of(
                            "Directory lists every customer with phones, owed balance, and wallet.",
                            "Filters: Added period, Outstanding only, and Inferred or Verified origin.",
                            "Customer page shows Owed on tab, Wallet, Loyalty points, and the credit statement.",
                            "Messaging sends WhatsApp and SMS payment reminders.",
                            "Customers come from till sales or M-Pesa prompts — badged Inferred or Verified."),
                    List.of(
                            "Who owes the most on tab?",
                            "How do I add a customer?",
                            "What does Inferred vs Verified mean?",
                            "How do I send a payment reminder?"));
        }
        if (matches(path, surf, "/credits", "credits", "on-tab")) {
            return guide(
                    "credits",
                    "On tab",
                    "Credit sales activity: what was charged, what was collected, and what customers still owe — with remind and mark-paid actions.",
                    List.of(
                            "The stat band shows put on tab, paid, and total owed right now.",
                            "Open tabs list each customer's balance with Remind and Mark paid.",
                            "Remind sends WhatsApp or SMS with a pay link; Mark paid settles cash or M-Pesa.",
                            "Payment claims review till, tab portal, and pay-link submissions."),
                    List.of(
                            "Who owes money right now?",
                            "How do I mark a tab as paid?",
                            "How do payment claims work?",
                            "What does the total owed include?"));
        }
        if (matches(path, surf, "/shifts", "shifts", "shift")) {
            return guide(
                    "shifts",
                    "Shifts",
                    "Open and close cashier shifts: register and opening float, closing cash count, variance, and drawouts.",
                    List.of(
                            "Open shift records the register, opening float count, and notes.",
                            "Close shift counts cash; variance is counted minus expected closing cash.",
                            "A variance of KES 500 or more needs a written reason to close.",
                            "Counts compares opening, expected, and closing denominations per note and coin.",
                            "Drawouts record cash removed from the till, pending approval."),
                    List.of(
                            "How do I open a shift?",
                            "The counted cash doesn't match the expected — what do I do?",
                            "What is a drawout?",
                            "How do I reconcile the drawer?"));
        }
        if (matches(path, surf, "/messages", "messages", "inbox")) {
            return guide(
                    "messages",
                    "Messages",
                    "Talk to Us messages from your storefront: read, filter, and reply by email, WhatsApp, or SMS.",
                    List.of(
                            "Inbox rows show customer name, message preview, and an unread dot.",
                            "All, Unread, and Read filters sit above the list.",
                            "Replies send directly via Email, WhatsApp, or SMS — there are no drafts.",
                            "Opening a message marks it read and shows the reply thread."),
                    List.of(
                            "How do I reply to a customer?",
                            "Which messages are still unread?",
                            "Can I send a WhatsApp reply?",
                            "Draft a reply to a customer message"));
        }
        if (matches(path, surf, "/storefront", "storefront", "web-orders")) {
            return guide(
                    "storefront",
                    "Pickup orders (web)",
                    "Incoming online orders for packing and counter pickup. Storefront settings live in Business settings.",
                    List.of(
                            "Open and All tabs list web orders; WhatsApp orders carry a channel badge.",
                            "Order detail shows lines, notes, total, and fulfillment actions with Print.",
                            "Turn the storefront on under Business settings → Online storefront.",
                            "WhatsApp ordering sends carts to your number; the catalog branch sets the stock and prices shoppers see."),
                    List.of(
                            "How do I turn on the online store?",
                            "How do I set up WhatsApp ordering?",
                            "Which branch powers the public catalog?",
                            "Where do I see pickup orders?"));
        }
        if (matches(path, surf, "/sales", "sales", "activity")) {
            return guide(
                    "sales",
                    "Sales activity",
                    "Live feed of till transactions with revenue, units, and refunds, plus filters by time, channel, status, and payment method.",
                    List.of(
                            "Each sale shows items, cashier, payment, and refund state.",
                            "Today and Yesterday run a live, auto-refreshing feed.",
                            "PDF exports a period summary; Record sale opens the quick till.",
                            "Filters: When, Channel (Walk-in or Online), Status (Done or Refund), Pay method.",
                            "The Transactions page adds voiding and per-receipt PDFs."),
                    List.of(
                            "How do I refund or void a sale?",
                            "Which payment method was used most today?",
                            "How do I download today's sales PDF?",
                            "Where do online store orders show up?"));
        }
        if (matches(path, surf, "/analytics", "analytics", "reports")) {
            return guide(
                    "analytics",
                    "Analytics",
                    "Sales performance dashboard: revenue, cost of goods sold, profit, and customers, with charts and period filters.",
                    List.of(
                            "KPIs: total revenue, cost of goods sold, profit, and customers.",
                            "Charts: product profit, staff revenue, branch COGS, monthly customer trend.",
                            "Period presets run from Today to 30 days, plus a Custom range.",
                            "Filter by Category and Branch; Activity and Shoppers open item and customer detail."),
                    List.of(
                            "How is profit calculated on this dashboard?",
                            "Which products and staff drive revenue this month?",
                            "How do I see sales by category?",
                            "What does the customer trend show?"));
        }
        if (matches(path, surf, "/discounts", "discounts", "promotions")) {
            return guide(
                    "discounts",
                    "Discounts",
                    "Time-bound promotions on shelf prices without changing your regular prices.",
                    List.of(
                            "Create discount applies to Products, Category, Supplier, or the Entire store.",
                            "Method is Percentage or Fixed amount, with a start and optional end.",
                            "Preview shows how many items are affected and sample new prices.",
                            "Statuses: Active, Scheduled, Paused, Expired — pause, resume, or publish."),
                    List.of(
                            "How do I create a percentage discount?",
                            "How do I pause a running promotion?",
                            "Can I discount everything from one supplier?",
                            "What does the preview tell me?"));
        }
        if (matches(path, surf, "/payments/day", "payments.day", "day-ledger")) {
            return guide(
                    "payments.day",
                    "Day ledger",
                    "Chronological list of a day's sale payments, with a tender mix breakdown, for reconciling the till.",
                    List.of(
                            "Day ledger lists sale payments for a chosen day.",
                            "The tender mix bar shows the day split by payment method.",
                            "Gateway verified marks payments that have a gateway receipt."),
                    List.of(
                            "What is the day ledger for?",
                            "How do I see a day's payments?",
                            "What does tender mix show?"));
        }
        if (matches(path, surf, "/payments/settings", "payments.settings", "gateways", "payouts")) {
            return guide(
                    "payments.settings",
                    "Gateways & payouts",
                    "Checkout payment methods and supplier payouts: M-Pesa (KopoKopo), till or paybill, and payout accounts.",
                    List.of(
                            "Add method covers M-Pesa STK, till, and paybill.",
                            "Each method follows a test → activate → webhooks flow.",
                            "Pay suppliers configures the payout accounts used for supplier payments."),
                    List.of(
                            "How do I set up M-Pesa payments?",
                            "How do I pay suppliers?",
                            "What methods can shoppers use at checkout?"));
        }
        if (matches(path, surf, "/marketplace", "marketplace")) {
            return guide(
                    "marketplace",
                    "Marketplace",
                    "Browse marketplace suppliers' products by area and category, build a cart, and send orders on WhatsApp.",
                    List.of(
                            "Products and Suppliers tabs switch between shelves and vendors.",
                            "Send order on WhatsApp, download PDF, copy the list, or copy an order link.",
                            "Connecting a supplier happens in Suppliers — Use supplier links their catalogue into your shop.",
                            "Supplier portal claim is separate — phone OTP at /supplier-portal/claim."),
                    List.of(
                            "How do I order from a marketplace supplier?",
                            "What does connecting a supplier change?",
                            "Can I download a supplier's price list?",
                            "How is the supplier portal separate?"));
        }
        if (path.startsWith("/supplier-portal") || surf.startsWith("supplier-portal")) {
            return guide(
                    "supplier-portal",
                    "Supplier portal",
                    "Supplier-facing hub: overview of shops, pending orders, outstanding balances, invoices, statements, messages, and deliveries.",
                    List.of(
                            "Overview shows pending orders, outstanding balances, and collections.",
                            "Orders inbox responds to purchase orders and ships them with tracking.",
                            "Invoices ledger shows open, partial, and paid bills per shop.",
                            "Statements are monthly ledgers per shop — download CSV or PDF.",
                            "Payout stores Paybill, till, mobile money, and bank details for shops to pay."),
                    List.of(
                            "How do I respond to an order?",
                            "Where do I update paybill details?",
                            "How do I download a statement?",
                            "How do I ship a delivery?"));
        }
        if (matches(path, surf, "/payroll", "payroll")) {
            return guide(
                    "payroll",
                    "Payroll",
                    "Monthly pay run: set each staff member's salary, log advances, then mark paid.",
                    List.of(
                            "Staff without a salary show as not set and cannot be marked paid until set.",
                            "Set salary keeps history — raises add a record with an effective date.",
                            "Advances are logged per staff member with amount, date, and note.",
                            "Net shows base minus advances for the month."),
                    List.of(
                            "Who gets paid this month?",
                            "How do I set a staff salary?",
                            "What is a salary advance?",
                            "Why is Net different from Base?"));
        }
        if (matches(path, surf, "/users", "users", "team")) {
            return guide(
                    "users",
                    "Users",
                    "Invite staff, assign roles and branches, and manage sign-in credentials.",
                    List.of(
                            "Invite user sends an email invite or sets a PIN directly.",
                            "Filters: status (Active, Invited, Suspended, Locked), role, and branch.",
                            "Role and branch change inline; departments apply to grocery clerks.",
                            "Owners reset passwords, view PINs, sign users out, and deactivate."),
                    List.of(
                            "How do I invite a new staff member?",
                            "What roles can I assign?",
                            "How do I reset someone's PIN?",
                            "How do I deactivate a user?"));
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
