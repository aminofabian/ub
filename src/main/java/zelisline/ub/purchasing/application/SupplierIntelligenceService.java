package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.purchasing.api.dto.PriceCompetitivenessRow;
import zelisline.ub.purchasing.api.dto.PurchasingIntelligenceDashboardResponse;
import zelisline.ub.purchasing.api.dto.SingleSourceRiskRow;
import zelisline.ub.purchasing.api.dto.SpendBySupplierCategoryRow;

@Service
@RequiredArgsConstructor
public class SupplierIntelligenceService {

    private final JdbcTemplate jdbc;

    private static final String Q_SPEND = """
            SELECT si.supplier_id AS supplier_id,
                   s.name AS supplier_name,
                   COALESCE(i.category_id, '_none') AS category_id,
                   COALESCE(c.name, 'Uncategorised') AS category_name,
                   COALESCE(SUM(sil.line_total), 0) AS spend_total
              FROM supplier_invoice_lines sil
              JOIN supplier_invoices si ON si.id = sil.invoice_id
              JOIN suppliers s ON s.id = si.supplier_id AND s.business_id = si.business_id
         LEFT JOIN items i ON i.id = sil.item_id AND i.business_id = si.business_id AND i.deleted_at IS NULL
         LEFT JOIN categories c ON c.id = i.category_id AND c.business_id = si.business_id
             WHERE si.business_id = ?
               AND si.status = 'posted'
               AND si.invoice_date >= ?
               AND si.invoice_date <= ?
               AND s.deleted_at IS NULL
          GROUP BY si.supplier_id, s.name, COALESCE(i.category_id, '_none'), COALESCE(c.name, 'Uncategorised')
          ORDER BY s.name, category_name
            """;

    private static final String Q_PRICE = """
            SELECT sil.id AS line_id,
                   si.id AS invoice_id,
                   si.supplier_id AS invoicing_supplier_id,
                   sil.item_id AS item_id,
                   i.sku AS item_sku,
                   sil.unit_cost AS paid_unit_cost,
                   psp.supplier_id AS primary_supplier_id,
                   psp.last_cost_price AS primary_last_cost
              FROM supplier_invoice_lines sil
              JOIN supplier_invoices si ON si.id = sil.invoice_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = si.business_id AND i.deleted_at IS NULL
              JOIN supplier_products psp ON psp.item_id = sil.item_id
               AND psp.is_primary = TRUE AND psp.active = TRUE AND psp.deleted_at IS NULL
             WHERE si.business_id = ?
               AND si.status = 'posted'
               AND si.invoice_date >= ?
               AND si.invoice_date <= ?
               AND sil.item_id IS NOT NULL
             ORDER BY si.invoice_date, sil.id
            """;

    private static final String Q_SINGLE_SOURCE = """
            SELECT i.id AS item_id,
                   i.sku AS sku,
                   i.name AS name,
                   sp.supplier_id AS sole_supplier_id,
                   s.name AS sole_supplier_name
              FROM items i
              JOIN supplier_products sp ON sp.item_id = i.id AND sp.active = TRUE AND sp.deleted_at IS NULL
              JOIN suppliers s ON s.id = sp.supplier_id AND s.business_id = i.business_id AND s.deleted_at IS NULL
             WHERE i.business_id = ?
               AND i.deleted_at IS NULL
               AND i.is_sellable = TRUE
               AND (SELECT COUNT(*) FROM supplier_products sp2
                     WHERE sp2.item_id = i.id AND sp2.active = TRUE AND sp2.deleted_at IS NULL) = 1
             ORDER BY i.sku
            """;

    private static final String INVOICE_BRANCH_FILTER = """

            AND COALESCE(
                (SELECT rps.branch_id FROM raw_purchase_sessions rps WHERE rps.id = si.raw_purchase_session_id LIMIT 1),
                (SELECT gr.branch_id FROM goods_receipts gr WHERE gr.id = si.goods_receipt_id LIMIT 1)
            ) = ?
            """;

    /**
     * Start of the first trailing clause ({@code GROUP BY} / {@code ORDER BY} / {@code LIMIT}).
     * Matches the keyword itself (not leading whitespace) so injected SQL stays separated.
     */
    private static final Pattern TRAILING_SQL_CLAUSE = Pattern.compile(
            "(?i)\\b(GROUP\\s+BY|ORDER\\s+BY|LIMIT)\\b");

    /**
     * Injects the branch predicate into the WHERE clause. Appending after
     * {@code ORDER BY}/{@code LIMIT} produces invalid SQL and surfaces as a
     * misleading schema-mismatch 500 when the UI always sends a branch id.
     */
    static String withInvoiceBranchFilter(String sql, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return sql;
        }
        Matcher m = TRAILING_SQL_CLAUSE.matcher(sql);
        if (!m.find()) {
            return sql + INVOICE_BRANCH_FILTER;
        }
        int insertAt = m.start();
        return sql.substring(0, insertAt) + INVOICE_BRANCH_FILTER + sql.substring(insertAt);
    }

    @Transactional(readOnly = true)
    public List<SpendBySupplierCategoryRow> spendBySupplierAndCategory(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        return jdbc.query(
                Q_SPEND,
                (rs, rowNum) -> new SpendBySupplierCategoryRow(
                        rs.getString("supplier_id"),
                        rs.getString("supplier_name"),
                        rs.getString("category_id"),
                        rs.getString("category_name"),
                        rs.getBigDecimal("spend_total").setScale(2, RoundingMode.HALF_UP)),
                businessId,
                Date.valueOf(w[0]),
                Date.valueOf(w[1]));
    }

    @Transactional(readOnly = true)
    public List<PriceCompetitivenessRow> priceCompetitivenessVsPrimary(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        List<PriceCompetitivenessRow> out = new ArrayList<>();
        jdbc.query(
                Q_PRICE,
                rs -> {
                    String invSupplier = rs.getString("invoicing_supplier_id");
                    String primarySupplier = rs.getString("primary_supplier_id");
                    BigDecimal paid = rs.getBigDecimal("paid_unit_cost");
                    BigDecimal primaryCost = rs.getBigDecimal("primary_last_cost");
                    BigDecimal variance = null;
                    if (primaryCost != null && primaryCost.signum() > 0) {
                        variance = paid.subtract(primaryCost)
                                .divide(primaryCost, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                    boolean fromPrimary = invSupplier != null && invSupplier.equals(primarySupplier);
                    out.add(new PriceCompetitivenessRow(
                            rs.getString("line_id"),
                            rs.getString("invoice_id"),
                            invSupplier,
                            rs.getString("item_id"),
                            rs.getString("item_sku"),
                            paid,
                            primarySupplier,
                            primaryCost,
                            variance,
                            fromPrimary));
                },
                businessId,
                Date.valueOf(w[0]),
                Date.valueOf(w[1]));
        return out;
    }

    private static LocalDate[] resolveWindow(LocalDate fromInclusive, LocalDate toInclusive) {
        LocalDate toEx = toInclusive != null ? toInclusive : LocalDate.now(ZoneOffset.UTC);
        LocalDate fromEx = fromInclusive != null ? fromInclusive : toEx.minusDays(90);
        if (fromEx.isAfter(toEx)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
        }
        return new LocalDate[] {fromEx, toEx};
    }

    @Transactional(readOnly = true)
    public List<SingleSourceRiskRow> singleSourceRiskItems(String businessId) {
        return jdbc.query(
                Q_SINGLE_SOURCE,
                (rs, rowNum) -> new SingleSourceRiskRow(
                        rs.getString("item_id"),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("sole_supplier_id"),
                        rs.getString("sole_supplier_name")),
                businessId);
    }

    @Transactional(readOnly = true)
    public PurchasingIntelligenceDashboardResponse getDashboard(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String branchId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date fromDate = Date.valueOf(w[0]);
        Date toDate = Date.valueOf(w[1]);
        String branchFilter = branchId != null ? branchId.trim() : "";

        // Summary
        var summaryRow = jdbc.queryForMap(
                withInvoiceBranchFilter("""
                SELECT COALESCE(SUM(sil.line_total), 0) AS total_spend,
                       COUNT(DISTINCT si.supplier_id) AS supplier_count,
                       COUNT(*) AS line_count,
                       COUNT(DISTINCT sil.item_id) AS item_count
                  FROM supplier_invoice_lines sil
                  JOIN supplier_invoices si ON si.id = sil.invoice_id
                  JOIN suppliers s ON s.id = si.supplier_id AND s.business_id = si.business_id
                 WHERE si.business_id = ?
                   AND si.status = 'posted'
                   AND si.invoice_date >= ?
                   AND si.invoice_date <= ?
                   AND s.deleted_at IS NULL
                """, branchFilter),
                branchFilter.isEmpty()
                        ? new Object[] {businessId, fromDate, toDate}
                        : new Object[] {businessId, fromDate, toDate, branchFilter});

        BigDecimal totalSpend = ((Number) summaryRow.get("total_spend")).doubleValue() == 0.0
                ? BigDecimal.ZERO
                : new BigDecimal(summaryRow.get("total_spend").toString()).setScale(2, RoundingMode.HALF_UP);
        int supplierCount = ((Number) summaryRow.get("supplier_count")).intValue();
        int lineCount = ((Number) summaryRow.get("line_count")).intValue();
        int itemCount = ((Number) summaryRow.get("item_count")).intValue();

        // Price variance data for alerts + summary
        List<PurchasingIntelligenceDashboardResponse.PriceVarianceAlert> priceAlerts = new ArrayList<>();
        BigDecimal[] totalVariance = { BigDecimal.ZERO };
        int[] varianceCount = { 0 };
        int[] abovePrimary = { 0 };
        int[] belowPrimary = { 0 };

        jdbc.query(
                withInvoiceBranchFilter(Q_PRICE, branchFilter),
                rs -> {
            BigDecimal paid = rs.getBigDecimal("paid_unit_cost");
            BigDecimal primaryCost = rs.getBigDecimal("primary_last_cost");
            BigDecimal variance = null;
            if (primaryCost != null && primaryCost.signum() > 0) {
                variance = paid.subtract(primaryCost)
                        .divide(primaryCost, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }
            if (variance != null) {
                totalVariance[0] = totalVariance[0].add(variance);
                varianceCount[0]++;
                if (variance.compareTo(BigDecimal.ZERO) > 0) abovePrimary[0]++;
                if (variance.compareTo(BigDecimal.ZERO) < 0) belowPrimary[0]++;
            }
            // Only include non-primary purchases with meaningful variance
            String invSupplier = rs.getString("invoicing_supplier_id");
            String primarySupplier = rs.getString("primary_supplier_id");
            boolean fromPrimary = invSupplier != null && invSupplier.equals(primarySupplier);
            if (!fromPrimary && variance != null && variance.abs().compareTo(new BigDecimal("5")) > 0) {
                priceAlerts.add(new PurchasingIntelligenceDashboardResponse.PriceVarianceAlert(
                        rs.getString("item_id"),
                        rs.getString("item_sku"),
                        rs.getString("invoice_id"),
                        paid,
                        primaryCost,
                        variance,
                        fromPrimary));
            }
        },
                branchFilter.isEmpty()
                        ? new Object[] {businessId, fromDate, toDate}
                        : new Object[] {businessId, fromDate, toDate, branchFilter});

        // Sort price alerts by absolute variance descending and limit
        priceAlerts.sort((a, b) -> b.variancePercent().abs().compareTo(a.variancePercent().abs()));
        List<PurchasingIntelligenceDashboardResponse.PriceVarianceAlert> limitedPriceAlerts =
                priceAlerts.size() > 20 ? priceAlerts.subList(0, 20) : priceAlerts;

        BigDecimal avgVariance = varianceCount[0] > 0
                ? totalVariance[0].divide(new BigDecimal(varianceCount[0]), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Spend trend by day (portable — avoid MySQL-only DATE_FORMAT for H2 tests)
        List<PurchasingIntelligenceDashboardResponse.SpendTrendPoint> spendTrend = jdbc.query(
                withInvoiceBranchFilter("""
                SELECT si.invoice_date AS dt,
                       COALESCE(SUM(sil.line_total), 0) AS spend
                  FROM supplier_invoice_lines sil
                  JOIN supplier_invoices si ON si.id = sil.invoice_id
                 WHERE si.business_id = ?
                   AND si.status = 'posted'
                   AND si.invoice_date >= ?
                   AND si.invoice_date <= ?
                 GROUP BY si.invoice_date
                 ORDER BY dt
                """, branchFilter),
                (rs, rowNum) -> new PurchasingIntelligenceDashboardResponse.SpendTrendPoint(
                        rs.getDate("dt").toLocalDate().toString(),
                        new BigDecimal(rs.getString("spend")).setScale(2, RoundingMode.HALF_UP)),
                branchFilter.isEmpty()
                        ? new Object[] {businessId, fromDate, toDate}
                        : new Object[] {businessId, fromDate, toDate, branchFilter});

        // Top suppliers
        List<PurchasingIntelligenceDashboardResponse.SupplierSpendPoint> topSuppliers = jdbc.query(
                withInvoiceBranchFilter("""
                SELECT si.supplier_id,
                       s.name AS supplier_name,
                       COALESCE(SUM(sil.line_total), 0) AS spend,
                       COUNT(*) AS line_count
                  FROM supplier_invoice_lines sil
                  JOIN supplier_invoices si ON si.id = sil.invoice_id
                  JOIN suppliers s ON s.id = si.supplier_id AND s.business_id = si.business_id
                 WHERE si.business_id = ?
                   AND si.status = 'posted'
                   AND si.invoice_date >= ?
                   AND si.invoice_date <= ?
                   AND s.deleted_at IS NULL
                 GROUP BY si.supplier_id, s.name
                 ORDER BY spend DESC
                 LIMIT 10
                """, branchFilter),
                (rs, rowNum) -> new PurchasingIntelligenceDashboardResponse.SupplierSpendPoint(
                        rs.getString("supplier_id"),
                        rs.getString("supplier_name"),
                        new BigDecimal(rs.getString("spend")).setScale(2, RoundingMode.HALF_UP),
                        rs.getInt("line_count")),
                branchFilter.isEmpty()
                        ? new Object[] {businessId, fromDate, toDate}
                        : new Object[] {businessId, fromDate, toDate, branchFilter});

        // Top categories
        List<PurchasingIntelligenceDashboardResponse.CategorySpendPoint> topCategories = jdbc.query(
                withInvoiceBranchFilter("""
                SELECT COALESCE(i.category_id, '_none') AS category_id,
                       COALESCE(c.name, 'Uncategorised') AS category_name,
                       COALESCE(SUM(sil.line_total), 0) AS spend,
                       COUNT(*) AS line_count
                  FROM supplier_invoice_lines sil
                  JOIN supplier_invoices si ON si.id = sil.invoice_id
             LEFT JOIN items i ON i.id = sil.item_id AND i.business_id = si.business_id AND i.deleted_at IS NULL
             LEFT JOIN categories c ON c.id = i.category_id AND c.business_id = si.business_id
                  JOIN suppliers s ON s.id = si.supplier_id AND s.business_id = si.business_id
                 WHERE si.business_id = ?
                   AND si.status = 'posted'
                   AND si.invoice_date >= ?
                   AND si.invoice_date <= ?
                   AND s.deleted_at IS NULL
                 GROUP BY COALESCE(i.category_id, '_none'), COALESCE(c.name, 'Uncategorised')
                 ORDER BY spend DESC
                 LIMIT 10
                """, branchFilter),
                (rs, rowNum) -> new PurchasingIntelligenceDashboardResponse.CategorySpendPoint(
                        rs.getString("category_id"),
                        rs.getString("category_name"),
                        new BigDecimal(rs.getString("spend")).setScale(2, RoundingMode.HALF_UP),
                        rs.getInt("line_count")),
                branchFilter.isEmpty()
                        ? new Object[] {businessId, fromDate, toDate}
                        : new Object[] {businessId, fromDate, toDate, branchFilter});

        // Single source risks
        List<SingleSourceRiskRow> risks = singleSourceRiskItems(businessId);

        // Insights
        List<PurchasingIntelligenceDashboardResponse.Insight> insights = new ArrayList<>();
        if (totalSpend.signum() > 0) {
            insights.add(new PurchasingIntelligenceDashboardResponse.Insight("info",
                    "Total spend of " + totalSpend.toPlainString() + " across " + supplierCount + " supplier" + (supplierCount == 1 ? "" : "s") + "."));
        }
        if (abovePrimary[0] > 0) {
            insights.add(new PurchasingIntelligenceDashboardResponse.Insight("warning",
                    abovePrimary[0] + " purchase line" + (abovePrimary[0] == 1 ? "" : "s") + " above primary supplier cost."));
        }
        if (belowPrimary[0] > 0) {
            insights.add(new PurchasingIntelligenceDashboardResponse.Insight("success",
                    belowPrimary[0] + " purchase line" + (belowPrimary[0] == 1 ? "" : "s") + " below primary supplier cost."));
        }
        if (!risks.isEmpty()) {
            insights.add(new PurchasingIntelligenceDashboardResponse.Insight("danger",
                    risks.size() + " sellable item" + (risks.size() == 1 ? "" : "s") + " with single-source supplier risk."));
        }

        return new PurchasingIntelligenceDashboardResponse(
                new PurchasingIntelligenceDashboardResponse.Summary(
                        totalSpend, supplierCount, lineCount, itemCount,
                        avgVariance, abovePrimary[0], belowPrimary[0], risks.size()),
                spendTrend,
                topSuppliers,
                topCategories,
                limitedPriceAlerts,
                risks,
                insights
        );
    }
}
