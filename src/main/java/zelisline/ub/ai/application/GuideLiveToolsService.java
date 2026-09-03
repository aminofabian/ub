package zelisline.ub.ai.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.credits.api.dto.OutstandingTabRowResponse;
import zelisline.ub.credits.application.CustomerDirectoryService;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.finance.api.dto.FinancePulseResponse;
import zelisline.ub.inventory.api.dto.analytics.BatchDashboardResponse;
import zelisline.ub.inventory.application.SupplyBatchAnalyticsService;
import zelisline.ub.messages.api.dto.ContactMessageListItemResponse;
import zelisline.ub.messages.application.ContactMessageService;
import zelisline.ub.purchasing.api.dto.ApAgingBuckets;
import zelisline.ub.purchasing.api.dto.ApAgingTotalsResponse;
import zelisline.ub.reporting.api.dto.OwnerDashboardResponse;
import zelisline.ub.reporting.application.DashboardService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Gathers LLM-safe live facts for Guide. Failures are swallowed into a note so chat still works.
 */
@Service
@RequiredArgsConstructor
public class GuideLiveToolsService {

    private static final Logger log = LoggerFactory.getLogger(GuideLiveToolsService.class);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private static final int PRODUCT_LIMIT = 12;
    private static final int TAB_LIMIT = 8;
    private static final int SUPPLIER_LIMIT = 8;
    private static final int CATEGORY_LIMIT = 15;

    /** Words that do not help find a product; stripped from a question before searching the catalog. */
    private static final Set<String> CATALOG_STOP_WORDS = Set.of(
            "what", "which", "whats", "is", "are", "the", "a", "an", "of", "for", "my", "our",
            "me", "i", "we", "you", "do", "does", "did", "how", "much", "many", "have", "has",
            "get", "check", "find", "show", "list", "see", "tell", "about", "please", "can",
            "now", "current", "price", "prices", "cost", "costs", "buying", "selling", "markup",
            "product", "products", "item", "items", "catalog", "catalogue", "inventory",
            "stock", "in", "on", "hand", "available", "barcode", "sku", "plu", "called",
            "named", "any", "all", "low", "out", "that", "there", "be",
            "ya", "ni", "na", "za", "je", "kwa", "ngapi", "bei", "gharama", "bidhaa",
            "aina", "zangu");

    private final DashboardService dashboardService;
    private final SupplyBatchAnalyticsService supplyBatchAnalyticsService;
    private final ContactMessageService contactMessageService;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final CustomerDirectoryService customerDirectoryService;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;

    public record LiveToolBundle(List<String> toolsUsed, String factsBlock) {}

    public LiveToolBundle gather(
            String businessId,
            String branchId,
            String surface,
            String skill,
            Map<String, String> entities,
            String userMessage
    ) {
        List<String> used = new ArrayList<>();
        StringBuilder facts = new StringBuilder();
        String surf = surface == null ? "" : surface.toLowerCase(Locale.ROOT);
        String skillNorm = skill == null ? "" : skill.toLowerCase(Locale.ROOT);
        String msg = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);

        // Customer receivables pages: "owe" here means customers owe the shop — never fetch supplier AP.
        boolean onReceivables = surfaceIs(surf, "customers") || surfaceIs(surf, "credits");

        boolean wantPulse = skillNorm.equals("morning_briefing")
                || surfaceIs(surf, "business")
                || surfaceIs(surf, "analytics")
                || surfaceIs(surf, "sales")
                || surfaceIs(surf, "shifts")
                || mentions(msg, "today", "revenue", "profit", "margin", "pulse", "morning", "sales", "how am i", "performing");
        boolean wantAp = skillNorm.equals("morning_briefing")
                || surfaceIs(surf, "suppliers")
                || surfaceIs(surf, "purchasing")
                || (mentions(msg, "owe", "payable", "aging", "supplier", "ap ", "balance", "overdue") && !onReceivables);
        boolean wantStock = skillNorm.equals("morning_briefing")
                || surfaceIs(surf, "inventory")
                || surfaceIs(surf, "stock")
                || mentions(msg, "stock", "restock", "expir", "low stock", "out of stock", "stockout");
        boolean wantInbox = skillNorm.equals("draft_message")
                || surfaceIs(surf, "message")
                || surfaceIs(surf, "messages")
                || surfaceIs(surf, "inbox")
                || mentions(msg, "draft", "reply", "message", "sms", "whatsapp", "email", "write");
        boolean wantProducts = surfaceIs(surf, "products")
                || surfaceIs(surf, "departments")
                || surfaceIs(surf, "categories")
                || surfaceIs(surf, "pricing")
                || mentions(msg, "product", "products", "item", "items", "catalog", "catalogue",
                        "sku", "barcode", "price", "prices", "cost", "buying", "selling",
                        "markup", "my products", "what do i sell", "on hand", "in stock");
        boolean wantCustomers = surfaceIs(surf, "customers")
                || surfaceIs(surf, "credits")
                || mentions(msg, "customer", "customers", "who owes", "debtor", "debtors",
                        "tab", "tabs", "credit customer");
        boolean wantSuppliers = surfaceIs(surf, "suppliers")
                || surfaceIs(surf, "purchasing")
                || (mentions(msg, "supplier", "suppliers", "vendor", "vendors", "who do i buy from")
                        && !onReceivables);
        boolean wantCategories = surfaceIs(surf, "categories")
                || mentions(msg, "categor", "grouped by");
        boolean wantDepartments = surfaceIs(surf, "departments")
                || mentions(msg, "department", "departments", "item type", "item types");

        // Owner summary already includes pulse + AP; prefer one call when either is needed.
        if (wantPulse || wantAp) {
            try {
                OwnerDashboardResponse summary =
                        dashboardService.ownerSummary(businessId, blankToNull(branchId), null);
                if (wantPulse && summary.pulseToday() != null) {
                    appendPulse(facts, summary.pulseToday());
                    used.add("getHubPulse");
                }
                if (wantAp && summary.payablesAging() != null) {
                    appendAp(facts, summary.payablesAging());
                    used.add("getApAging");
                }
                if (wantPulse && summary.topSkusLast30Days() != null && !summary.topSkusLast30Days().isEmpty()) {
                    facts.append("Top SKUs (last 30 days revenue):\n");
                    int n = 0;
                    for (OwnerDashboardResponse.TopSkuByRevenue sku : summary.topSkusLast30Days()) {
                        if (n++ >= 5) {
                            break;
                        }
                        facts.append("- ")
                                .append(nullToDash(sku.itemName()))
                                .append(": ")
                                .append(money(sku.revenueLast30Days()))
                                .append('\n');
                    }
                    used.add("getTopSkus");
                }
            } catch (Exception ex) {
                log.debug("Guide live hub tools failed: {}", ex.getMessage());
                facts.append("Note: live hub/AP data unavailable (").append(shortErr(ex)).append(").\n");
            }
        }

        if (wantStock) {
            try {
                LocalDate to = LocalDate.now(NAIROBI);
                LocalDate from = to.minusDays(30);
                BatchDashboardResponse dash = supplyBatchAnalyticsService.getDashboard(
                        businessId,
                        blankToNull(branchId),
                        from.toString(),
                        to.toString());
                appendStock(facts, dash);
                used.add("getStockoutRisks");
            } catch (Exception ex) {
                log.debug("Guide live stock tools failed: {}", ex.getMessage());
                facts.append("Note: live stock risk data unavailable (").append(shortErr(ex)).append(").\n");
            }
        }

        if (wantInbox) {
            try {
                var page = contactMessageService.listTenant(
                        businessId, null, PageRequest.of(0, 5));
                facts.append("Recent inbox messages (PII redacted; use for draft tone only):\n");
                if (page.isEmpty()) {
                    facts.append("- (none)\n");
                } else {
                    for (ContactMessageListItemResponse row : page.getContent()) {
                        facts.append("- From \"")
                                .append(nullToDash(row.name()))
                                .append("\": ")
                                .append(nullToDash(row.preview()))
                                .append(" [")
                                .append(nullToDash(row.status()))
                                .append("]\n");
                    }
                }
                used.add("listRecentInbox");
                String shopName = entities != null ? entities.get("shopName") : null;
                String supplierName = entities != null ? entities.get("supplierName") : null;
                if (shopName != null && !shopName.isBlank()) {
                    facts.append("Context shop name: ").append(shopName.trim()).append('\n');
                }
                if (supplierName != null && !supplierName.isBlank()) {
                    facts.append("Context supplier name: ").append(supplierName.trim()).append('\n');
                }
            } catch (Exception ex) {
                log.debug("Guide live inbox tools failed: {}", ex.getMessage());
                facts.append("Note: inbox preview unavailable (").append(shortErr(ex)).append(").\n");
            }
        }

        if (wantProducts) {
            try {
                String query = extractCatalogQuery(msg);
                Page<Item> page = itemRepository.searchActiveByBusiness(
                        businessId, query, PageRequest.of(0, PRODUCT_LIMIT));
                long activeCount =
                        itemRepository.countByBusinessIdAndDeletedAtIsNullAndActiveTrue(businessId);
                facts.append("Product catalog");
                if (query != null) {
                    facts.append(" matching \"").append(query).append('"');
                }
                facts.append(":\n");
                facts.append("- Active products total: ").append(activeCount).append('\n');
                if (page.isEmpty()) {
                    facts.append("- (no matching products)\n");
                } else {
                    int n = 0;
                    for (Item item : page.getContent()) {
                        if (n++ >= PRODUCT_LIMIT) {
                            break;
                        }
                        // Skip group parents (variant families) — they are labels, not sellable SKUs.
                        if (!item.isActive() || isGroupLabelParent(item)) {
                            continue;
                        }
                        facts.append("- ")
                                .append(nullToDash(item.getName()))
                                .append(" [")
                                .append(nullToDash(item.getSku()))
                                .append("] sell ")
                                .append(money(item.getBundlePrice()))
                                .append(", cost ")
                                .append(money(item.getBuyingPrice()))
                                .append(", on hand ")
                                .append(money(item.getCurrentStock()))
                                .append('\n');
                    }
                }
                used.add("searchProducts");
            } catch (Exception ex) {
                log.debug("Guide live product catalog failed: {}", ex.getMessage());
                facts.append("Note: live product catalog unavailable (")
                        .append(shortErr(ex))
                        .append(").\n");
            }
        }

        if (wantCustomers) {
            try {
                List<OutstandingTabRowResponse> tabs =
                        customerDirectoryService.listOutstandingTabs(businessId, null);
                long totalCustomers = customerRepository
                        .findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(
                                businessId, PageRequest.of(0, 1))
                        .getTotalElements();
                facts.append("Customers:\n");
                facts.append("- Total customers: ").append(totalCustomers).append('\n');
                if (tabs.isEmpty()) {
                    facts.append("- No customers with an open tab.\n");
                } else {
                    facts.append("Customers with open tabs (up to ").append(TAB_LIMIT).append("):\n");
                    int n = 0;
                    for (OutstandingTabRowResponse tab : tabs) {
                        if (n++ >= TAB_LIMIT) {
                            break;
                        }
                        facts.append("- ")
                                .append(nullToDash(tab.name()))
                                .append(" owes ")
                                .append(money(tab.balanceOwed()));
                        if (tab.creditSuspended()) {
                            facts.append(" [tab suspended]");
                        }
                        if (tab.primaryPhone() != null && !tab.primaryPhone().isBlank()) {
                            facts.append(" (").append(tab.primaryPhone().trim()).append(')');
                        }
                        facts.append('\n');
                    }
                }
                used.add("listCustomerTabs");
            } catch (Exception ex) {
                log.debug("Guide live customer tools failed: {}", ex.getMessage());
                facts.append("Note: live customer data unavailable (")
                        .append(shortErr(ex))
                        .append(").\n");
            }
        }

        if (wantSuppliers) {
            try {
                Page<Supplier> page = supplierRepository.searchSuppliers(
                        businessId, null, null, PageRequest.of(0, SUPPLIER_LIMIT));
                facts.append("Suppliers (up to ").append(SUPPLIER_LIMIT).append("):\n");
                if (page.isEmpty()) {
                    facts.append("- (none)\n");
                } else {
                    for (Supplier s : page.getContent()) {
                        facts.append("- ")
                                .append(nullToDash(s.getName()))
                                .append(" [")
                                .append(nullToDash(s.getCode()))
                                .append("] ")
                                .append(nullToDash(s.getSupplierType()));
                        if (s.getStatus() != null && !s.getStatus().isBlank()) {
                            facts.append(", ").append(s.getStatus().trim());
                        }
                        if (s.getCreditTermsDays() != null) {
                            facts.append(", terms ").append(s.getCreditTermsDays()).append("d");
                        }
                        facts.append('\n');
                    }
                }
                used.add("listSuppliers");
            } catch (Exception ex) {
                log.debug("Guide live supplier tools failed: {}", ex.getMessage());
                facts.append("Note: live supplier data unavailable (")
                        .append(shortErr(ex))
                        .append(").\n");
            }
        }

        if (wantCategories) {
            try {
                List<Category> cats = categoryRepository.findByBusinessIdOrderByPositionAsc(businessId);
                Map<String, Long> productCounts = new HashMap<>();
                for (Object[] row : itemRepository.countActiveItemsByCategory(businessId)) {
                    if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                        productCounts.put(row[0].toString(), ((Number) row[1]).longValue());
                    }
                }
                long activeCats = cats.stream().filter(Category::isActive).count();
                facts.append("Categories:\n");
                facts.append("- Total categories: ")
                        .append(cats.size())
                        .append(" (")
                        .append(activeCats)
                        .append(" active)\n");
                if (cats.isEmpty()) {
                    facts.append("- (none yet — create categories on the Categories page)\n");
                } else {
                    facts.append("Categories (up to ").append(CATEGORY_LIMIT).append("):\n");
                    int n = 0;
                    for (Category cat : cats) {
                        if (n++ >= CATEGORY_LIMIT) {
                            break;
                        }
                        facts.append("- ").append(nullToDash(cat.getName()));
                        if (cat.getParentId() != null && !cat.getParentId().isBlank()) {
                            String parentName = categoryName(cats, cat.getParentId());
                            if (parentName != null) {
                                facts.append(" (under ").append(parentName).append(')');
                            }
                        }
                        facts.append(": ")
                                .append(productCounts.getOrDefault(cat.getId(), 0L))
                                .append(" products");
                        if (cat.getDefaultMarkupPct() != null) {
                            facts.append(", markup ").append(money(cat.getDefaultMarkupPct())).append('%');
                        }
                        if (!cat.isActive()) {
                            facts.append(", inactive");
                        }
                        facts.append('\n');
                    }
                }
                used.add("listCategories");
            } catch (Exception ex) {
                log.debug("Guide live category tools failed: {}", ex.getMessage());
                facts.append("Note: live category data unavailable (")
                        .append(shortErr(ex))
                        .append(").\n");
            }
        }

        if (wantDepartments) {
            try {
                List<ItemType> types = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
                facts.append("Departments:\n");
                if (types.isEmpty()) {
                    facts.append("- (none)\n");
                } else {
                    for (ItemType t : types) {
                        facts.append("- ")
                                .append(nullToDash(t.getLabel()));
                        if (t.getTypeKey() != null && !t.getTypeKey().isBlank()) {
                            facts.append(" [").append(t.getTypeKey().trim()).append(']');
                        }
                        if (t.isDefault()) {
                            facts.append(", default");
                        }
                        if (!t.isActive()) {
                            facts.append(", inactive");
                        }
                        facts.append('\n');
                    }
                }
                used.add("listDepartments");
            } catch (Exception ex) {
                log.debug("Guide live department tools failed: {}", ex.getMessage());
                facts.append("Note: live department data unavailable (")
                        .append(shortErr(ex))
                        .append(").\n");
            }
        }

        if (used.isEmpty()) {
            return new LiveToolBundle(List.of(), "");
        }
        return new LiveToolBundle(List.copyOf(used), facts.toString());
    }

    /** Pull a short product search term out of a question, or null when the message is generic. */
    private static String extractCatalogQuery(String msg) {
        if (msg == null || msg.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String token : msg.toLowerCase(Locale.ROOT).split("[^a-z0-9.]+")) {
            if (token.isEmpty() || CATALOG_STOP_WORDS.contains(token)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token);
            if (sb.length() >= 40) {
                break;
            }
        }
        String q = sb.toString().trim();
        return q.isEmpty() ? null : q;
    }

    /**
     * A non-sellable parent that only groups variant SKUs is a label, not a product a
     * shopkeeper would look up by name/price. Mirrors the catalog's {@code groupLabelOnly} rule.
     */
    private boolean isGroupLabelParent(Item item) {
        if (item.getVariantOfItemId() != null || item.isSellable()) {
            return false;
        }
        return itemRepository.existsByBusinessIdAndVariantOfItemIdAndDeletedAtIsNull(
                item.getBusinessId(), item.getId());
    }

    private static String categoryName(List<Category> cats, String id) {
        return cats.stream()
                .filter(c -> c.getId().equals(id))
                .map(Category::getName)
                .findFirst()
                .orElse(null);
    }

    private static void appendPulse(StringBuilder facts, FinancePulseResponse pulse) {
        facts.append("Today's pulse (").append(pulse.date()).append("):\n");
        facts.append("- Sales count: ").append(pulse.salesCount()).append('\n');
        facts.append("- Revenue: ").append(money(pulse.revenue())).append('\n');
        facts.append("- COGS: ").append(money(pulse.cogs())).append('\n');
        facts.append("- Gross profit: ").append(money(pulse.grossProfit())).append('\n');
        facts.append("- Gross margin %: ").append(money(pulse.grossMarginPct())).append('\n');
        facts.append("- Expenses: ").append(money(pulse.expensesTotal())).append('\n');
        facts.append("- Net operating: ").append(money(pulse.netOperating())).append('\n');
        facts.append("- Open shifts: ").append(pulse.openShifts()).append('\n');
    }

    private static void appendAp(StringBuilder facts, ApAgingTotalsResponse aging) {
        facts.append("AP aging as of ").append(aging.asOf()).append(":\n");
        facts.append("- Total open: ").append(money(aging.totalOpen())).append('\n');
        facts.append("- Supplier prepayments: ")
                .append(money(aging.totalSupplierPrepaymentBalance()))
                .append('\n');
        ApAgingBuckets b = aging.buckets();
        if (b != null) {
            facts.append("- Current: ").append(money(b.current())).append('\n');
            facts.append("- 1–30 days: ").append(money(b.days1To30())).append('\n');
            facts.append("- 31–60 days: ").append(money(b.days31To60())).append('\n');
            facts.append("- 61–90 days: ").append(money(b.days61To90())).append('\n');
            facts.append("- Over 90 days: ").append(money(b.daysOver90())).append('\n');
        }
    }

    private static void appendStock(StringBuilder facts, BatchDashboardResponse dash) {
        facts.append("Stock risks:\n");
        if (dash.lowStockProducts() != null && !dash.lowStockProducts().isEmpty()) {
            facts.append("Low stock (up to 5):\n");
            int n = 0;
            for (BatchDashboardResponse.LowStockProductPoint row : dash.lowStockProducts()) {
                if (n++ >= 5) {
                    break;
                }
                facts.append("- ")
                        .append(nullToDash(row.itemName()))
                        .append(" on hand ")
                        .append(money(row.currentStock()))
                        .append(" / reorder ")
                        .append(money(row.reorderLevel()))
                        .append('\n');
            }
        } else {
            facts.append("- No low-stock rows flagged.\n");
        }
        if (dash.expiringBatches() != null && !dash.expiringBatches().isEmpty()) {
            facts.append("Expiring soon (up to 5):\n");
            int n = 0;
            for (BatchDashboardResponse.ExpiringBatchPoint row : dash.expiringBatches()) {
                if (n++ >= 5) {
                    break;
                }
                facts.append("- ")
                        .append(nullToDash(row.itemName()))
                        .append(" qty ")
                        .append(money(row.quantityRemaining()))
                        .append(" expires ")
                        .append(nullToDash(row.expiryDate()))
                        .append(" (")
                        .append(row.daysUntilExpiry())
                        .append("d)\n");
            }
        }
        if (dash.alerts() != null) {
            for (BatchDashboardResponse.BatchAlert alert : dash.alerts()) {
                if (alert == null || alert.message() == null) {
                    continue;
                }
                facts.append("- Alert [")
                        .append(nullToDash(alert.kind()))
                        .append("]: ")
                        .append(alert.message())
                        .append('\n');
            }
        }
    }

    private static boolean surfaceIs(String surface, String token) {
        return surface.equals(token) || surface.startsWith(token + ".");
    }

    private static boolean mentions(String msg, String... needles) {
        for (String n : needles) {
            if (msg.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String money(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String shortErr(Exception ex) {
        String m = ex.getMessage();
        if (m == null || m.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return m.length() <= 80 ? m : m.substring(0, 80);
    }
}
