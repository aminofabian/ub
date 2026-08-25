package zelisline.ub.ai.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import zelisline.ub.finance.api.dto.FinancePulseResponse;
import zelisline.ub.inventory.api.dto.analytics.BatchDashboardResponse;
import zelisline.ub.inventory.application.SupplyBatchAnalyticsService;
import zelisline.ub.messages.api.dto.ContactMessageListItemResponse;
import zelisline.ub.messages.application.ContactMessageService;
import zelisline.ub.purchasing.api.dto.ApAgingBuckets;
import zelisline.ub.purchasing.api.dto.ApAgingTotalsResponse;
import zelisline.ub.reporting.api.dto.OwnerDashboardResponse;
import zelisline.ub.reporting.application.DashboardService;

/**
 * Gathers LLM-safe live facts for Guide. Failures are swallowed into a note so chat still works.
 */
@Service
@RequiredArgsConstructor
public class GuideLiveToolsService {

    private static final Logger log = LoggerFactory.getLogger(GuideLiveToolsService.class);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final DashboardService dashboardService;
    private final SupplyBatchAnalyticsService supplyBatchAnalyticsService;
    private final ContactMessageService contactMessageService;

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

        boolean wantPulse = skillNorm.equals("morning_briefing")
                || surfaceIs(surf, "business")
                || surfaceIs(surf, "analytics")
                || mentions(msg, "today", "revenue", "profit", "margin", "pulse", "morning", "sales", "how am i", "performing");
        boolean wantAp = skillNorm.equals("morning_briefing")
                || surfaceIs(surf, "suppliers")
                || surfaceIs(surf, "purchasing")
                || mentions(msg, "owe", "payable", "aging", "supplier", "ap ", "balance", "overdue");
        boolean wantStock = skillNorm.equals("morning_briefing")
                || surfaceIs(surf, "inventory")
                || surfaceIs(surf, "stock")
                || mentions(msg, "stock", "restock", "expir", "low stock", "out of stock", "stockout");
        boolean wantInbox = skillNorm.equals("draft_message")
                || surfaceIs(surf, "message")
                || surfaceIs(surf, "inbox")
                || mentions(msg, "draft", "reply", "message", "sms", "whatsapp", "email", "write");

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

        if (used.isEmpty()) {
            return new LiveToolBundle(List.of(), "");
        }
        return new LiveToolBundle(List.copyOf(used), facts.toString());
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
