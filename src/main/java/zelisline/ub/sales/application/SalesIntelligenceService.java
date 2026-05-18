package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.CategoryDailyRevenueRow;
import zelisline.ub.sales.api.dto.ItemRevenueRow;
import zelisline.ub.sales.api.dto.PaymentMethodBreakdownRow;
import zelisline.ub.sales.api.dto.RecentSaleRow;
import zelisline.ub.sales.api.dto.RevenueByCategoryRow;
import zelisline.ub.sales.api.dto.StaffPerformanceRow;

@Service
@RequiredArgsConstructor
public class SalesIntelligenceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final JdbcTemplate jdbc;

    private static final String Q_GROSS = """
            SELECT COALESCE(i.category_id, '_none') AS category_id,
                   COALESCE(c.name, 'Uncategorised') AS category_name,
                   COALESCE(SUM(sil.line_total), 0) AS amt,
                   COALESCE(SUM(sil.profit), 0) AS profit_amt
              FROM sale_items sil
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
         LEFT JOIN categories c ON c.id = i.category_id AND c.business_id = s.business_id
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR i.category_id = ?)
          GROUP BY COALESCE(i.category_id, '_none'), COALESCE(c.name, 'Uncategorised')
            """;

    private static final String Q_REFUNDS = """
            SELECT COALESCE(i.category_id, '_none') AS category_id,
                   COALESCE(c.name, 'Uncategorised') AS category_name,
                   COALESCE(SUM(rl.amount), 0) AS amt,
                   COALESCE(SUM(sil.profit * (rl.quantity / NULLIF(sil.quantity, 0))), 0) AS profit_amt
              FROM refund_lines rl
              JOIN refunds r ON r.id = rl.refund_id
              JOIN sale_items sil ON sil.id = rl.sale_item_id
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = r.business_id AND i.deleted_at IS NULL
         LEFT JOIN categories c ON c.id = i.category_id AND c.business_id = r.business_id
             WHERE r.business_id = ?
               AND r.status = ?
               AND CAST(r.refunded_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR i.category_id = ?)
          GROUP BY COALESCE(i.category_id, '_none'), COALESCE(c.name, 'Uncategorised')
            """;

    private static final String Q_DAILY = """
            SELECT CAST(s.sold_at AS DATE) AS sale_date,
                   COALESCE(SUM(sil.line_total), 0) AS gross,
                   COALESCE(SUM(sil.profit), 0) AS profit_gross
              FROM sale_items sil
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND i.category_id = ?
          GROUP BY CAST(s.sold_at AS DATE)
            """;

    private static final String Q_DAILY_REFUNDS = """
            SELECT CAST(r.refunded_at AS DATE) AS refund_date,
                   COALESCE(SUM(rl.amount), 0) AS refund_amt,
                   COALESCE(SUM(sil.profit * (rl.quantity / NULLIF(sil.quantity, 0))), 0) AS profit_refund
              FROM refund_lines rl
              JOIN refunds r ON r.id = rl.refund_id
              JOIN sale_items sil ON sil.id = rl.sale_item_id
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = r.business_id AND i.deleted_at IS NULL
             WHERE r.business_id = ?
               AND r.status = ?
               AND CAST(r.refunded_at AS DATE) BETWEEN ? AND ?
               AND i.category_id = ?
          GROUP BY CAST(r.refunded_at AS DATE)
            """;

    private static final String Q_ITEMS = """
            SELECT sil.item_id,
                   i.name AS item_name,
                   i.sku,
                   COALESCE(SUM(sil.quantity), 0) AS qty_sold,
                   COALESCE(SUM(sil.line_total), 0) AS gross,
                   COALESCE(SUM(sil.profit), 0) AS profit_gross
              FROM sale_items sil
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND i.category_id = ?
          GROUP BY sil.item_id, i.name, i.sku
            """;

    private static final String Q_ITEMS_REFUNDS = """
            SELECT sil.item_id,
                   COALESCE(SUM(rl.quantity), 0) AS qty_refunded,
                   COALESCE(SUM(rl.amount), 0) AS refund_amt,
                   COALESCE(SUM(sil.profit * (rl.quantity / NULLIF(sil.quantity, 0))), 0) AS profit_refund
              FROM refund_lines rl
              JOIN refunds r ON r.id = rl.refund_id
              JOIN sale_items sil ON sil.id = rl.sale_item_id
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = r.business_id AND i.deleted_at IS NULL
             WHERE r.business_id = ?
               AND r.status = ?
               AND CAST(r.refunded_at AS DATE) BETWEEN ? AND ?
               AND i.category_id = ?
          GROUP BY sil.item_id
            """;

    private static final String Q_ITEMS_BY_DATE = """
            SELECT sil.item_id,
                   COALESCE(SUM(sil.quantity), 0) AS qty_sold
              FROM sale_items sil
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
          GROUP BY sil.item_id
            """;

    private static final String Q_ITEMS_REFUNDS_BY_DATE = """
            SELECT sil.item_id,
                   COALESCE(SUM(rl.quantity), 0) AS qty_refunded
              FROM refund_lines rl
              JOIN refunds r ON r.id = rl.refund_id
              JOIN sale_items sil ON sil.id = rl.sale_item_id
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = r.business_id AND i.deleted_at IS NULL
             WHERE r.business_id = ?
               AND r.status = ?
               AND CAST(r.refunded_at AS DATE) BETWEEN ? AND ?
          GROUP BY sil.item_id
            """;

    @Transactional(readOnly = true)
    public List<RevenueByCategoryRow> netRevenueByCategory(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String categoryId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String catFilter = (categoryId != null && !categoryId.isBlank()) ? categoryId : null;

        Map<String, Agg> byCat = new HashMap<>();

        jdbc.query(
                Q_GROSS,
                rs -> {
                    String id = rs.getString("category_id");
                    String name = rs.getString("category_name");
                    BigDecimal amt = rs.getBigDecimal("amt").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profit = rs.getBigDecimal("profit_amt").setScale(2, RoundingMode.HALF_UP);
                    byCat.merge(id, new Agg(name, amt, ZERO, profit, ZERO), SalesIntelligenceService::combineAgg);
                },
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                from,
                to,
                catFilter,
                catFilter);

        jdbc.query(
                Q_REFUNDS,
                rs -> {
                    String id = rs.getString("category_id");
                    String name = rs.getString("category_name");
                    BigDecimal amt = rs.getBigDecimal("amt").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profit = rs.getBigDecimal("profit_amt").setScale(2, RoundingMode.HALF_UP);
                    byCat.merge(id, new Agg(name, ZERO, amt, ZERO, profit), SalesIntelligenceService::combineAgg);
                },
                businessId,
                SalesConstants.REFUND_STATUS_COMPLETED,
                from,
                to,
                catFilter,
                catFilter);

        List<RevenueByCategoryRow> out = new ArrayList<>();
        for (Map.Entry<String, Agg> e : byCat.entrySet()) {
            Agg a = e.getValue();
            BigDecimal net = a.gross.subtract(a.refunds).setScale(2, RoundingMode.HALF_UP);
            BigDecimal netProfit = a.profitGross.subtract(a.profitRefunds).setScale(2, RoundingMode.HALF_UP);
            if (net.signum() == 0 && netProfit.signum() == 0) {
                continue;
            }
            out.add(new RevenueByCategoryRow(e.getKey(), a.name, net, netProfit));
        }
        out.sort(Comparator.comparing(RevenueByCategoryRow::netRevenue).reversed());
        return out;
    }

    @Transactional(readOnly = true)
    public List<CategoryDailyRevenueRow> dailyRevenueByCategory(
            String businessId,
            String categoryId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        if (categoryId == null || categoryId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId is required");
        }
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);

        Map<LocalDate, DailyAgg> byDay = new HashMap<>();

        jdbc.query(
                Q_DAILY,
                rs -> {
                    LocalDate d = rs.getDate("sale_date").toLocalDate();
                    BigDecimal gross = rs.getBigDecimal("gross").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profit = rs.getBigDecimal("profit_gross").setScale(2, RoundingMode.HALF_UP);
                    byDay.merge(d, new DailyAgg(gross, ZERO, profit, ZERO), SalesIntelligenceService::combineDaily);
                },
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                from,
                to,
                categoryId);

        jdbc.query(
                Q_DAILY_REFUNDS,
                rs -> {
                    LocalDate d = rs.getDate("refund_date").toLocalDate();
                    BigDecimal refund = rs.getBigDecimal("refund_amt").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profit = rs.getBigDecimal("profit_refund").setScale(2, RoundingMode.HALF_UP);
                    byDay.merge(d, new DailyAgg(ZERO, refund, ZERO, profit), SalesIntelligenceService::combineDaily);
                },
                businessId,
                SalesConstants.REFUND_STATUS_COMPLETED,
                from,
                to,
                categoryId);

        List<CategoryDailyRevenueRow> out = new ArrayList<>();
        for (Map.Entry<LocalDate, DailyAgg> e : byDay.entrySet()) {
            DailyAgg a = e.getValue();
            BigDecimal net = a.gross.subtract(a.refunds).setScale(2, RoundingMode.HALF_UP);
            BigDecimal netProfit = a.profitGross.subtract(a.profitRefunds).setScale(2, RoundingMode.HALF_UP);
            out.add(new CategoryDailyRevenueRow(e.getKey(), a.gross, a.refunds, net, netProfit));
        }
        out.sort(Comparator.comparing(CategoryDailyRevenueRow::date));
        return out;
    }

    @Transactional(readOnly = true)
    public List<ItemRevenueRow> revenueByCategoryItems(
            String businessId,
            String categoryId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        if (categoryId == null || categoryId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId is required");
        }
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);

        Map<String, ItemAgg> byItem = new HashMap<>();

        jdbc.query(
                Q_ITEMS,
                rs -> {
                    String id = rs.getString("item_id");
                    String name = rs.getString("item_name");
                    String sku = rs.getString("sku");
                    BigDecimal qty = rs.getBigDecimal("qty_sold").setScale(4, RoundingMode.HALF_UP);
                    BigDecimal gross = rs.getBigDecimal("gross").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profit = rs.getBigDecimal("profit_gross").setScale(2, RoundingMode.HALF_UP);
                    byItem.merge(id, new ItemAgg(name, sku, qty, gross, ZERO, profit, ZERO), SalesIntelligenceService::combineItem);
                },
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                from,
                to,
                categoryId);

        jdbc.query(
                Q_ITEMS_REFUNDS,
                rs -> {
                    String id = rs.getString("item_id");
                    BigDecimal qty = rs.getBigDecimal("qty_refunded").setScale(4, RoundingMode.HALF_UP);
                    BigDecimal refund = rs.getBigDecimal("refund_amt").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profit = rs.getBigDecimal("profit_refund").setScale(2, RoundingMode.HALF_UP);
                    byItem.merge(id, new ItemAgg(null, null, qty.negate(), ZERO, refund, ZERO, profit), SalesIntelligenceService::combineItem);
                },
                businessId,
                SalesConstants.REFUND_STATUS_COMPLETED,
                from,
                to,
                categoryId);

        List<ItemRevenueRow> out = new ArrayList<>();
        for (Map.Entry<String, ItemAgg> e : byItem.entrySet()) {
            ItemAgg a = e.getValue();
            BigDecimal net = a.gross.subtract(a.refunds).setScale(2, RoundingMode.HALF_UP);
            BigDecimal netProfit = a.profitGross.subtract(a.profitRefunds).setScale(2, RoundingMode.HALF_UP);
            if (net.signum() == 0 && netProfit.signum() == 0) {
                continue;
            }
            out.add(new ItemRevenueRow(e.getKey(), a.name, a.sku, a.qty, a.gross, a.refunds, net, netProfit));
        }
        out.sort(Comparator.comparing(ItemRevenueRow::netRevenue).reversed());
        return out;
    }

    /**
     * Returns a map of itemId → net quantity sold (gross minus refunds) for the
     * given date range, across all categories. Used by stock-take reconciliation.
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> quantitySoldByItem(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);

        Map<String, BigDecimal> byItem = new HashMap<>();

        jdbc.query(
                Q_ITEMS_BY_DATE,
                rs -> {
                    String id = rs.getString("item_id");
                    BigDecimal qty = rs.getBigDecimal("qty_sold").setScale(4, RoundingMode.HALF_UP);
                    byItem.merge(id, qty, BigDecimal::add);
                },
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                from,
                to);

        jdbc.query(
                Q_ITEMS_REFUNDS_BY_DATE,
                rs -> {
                    String id = rs.getString("item_id");
                    BigDecimal qty = rs.getBigDecimal("qty_refunded").setScale(4, RoundingMode.HALF_UP);
                    byItem.merge(id, qty.negate(), BigDecimal::add);
                },
                businessId,
                SalesConstants.REFUND_STATUS_COMPLETED,
                from,
                to);

        return byItem;
    }

    private static final String Q_RECENT_SALES = """
            SELECT s.id AS sale_id,
                   s.sold_at,
                   COALESCE(NULLIF(TRIM(u.name), ''), u.email, s.sold_by) AS cashier_name,
                   COALESCE(cu.name, '') AS customer_name,
                   (SELECT CASE
                        WHEN COUNT(DISTINCT sp2.method) > 1 THEN 'split'
                        ELSE COALESCE(MAX(sp2.method), 'unknown')
                    END
                      FROM sale_payments sp2
                     WHERE sp2.sale_id = s.id) AS payment_method,
                   (SELECT STRING_AGG(DISTINCT sp2.method, ',' ORDER BY sp2.method)
                      FROM sale_payments sp2
                     WHERE sp2.sale_id = s.id) AS payment_methods,
                   sil.item_id,
                   i.name AS item_name,
                   sil.quantity,
                   sil.unit_price,
                   sil.line_total,
                   sil.profit,
                   s.status,
                   'walk_in' AS channel
              FROM sale_items sil
              JOIN sales s ON s.id = sil.sale_id
              JOIN items i ON i.id = sil.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
         LEFT JOIN users u ON u.id = s.sold_by AND u.business_id = s.business_id AND u.deleted_at IS NULL
         LEFT JOIN customers cu ON cu.id = s.customer_id AND cu.business_id = s.business_id
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR s.branch_id = ?)
          ORDER BY s.sold_at DESC
             LIMIT 500
            """;

    private static final String Q_RECENT_WEB_ORDER_LINES = """
            SELECT wo.id AS sale_id,
                   wo.created_at AS sold_at,
                   '' AS cashier_name,
                   COALESCE(wo.customer_name, '') AS customer_name,
                   'online' AS payment_method,
                   'online' AS payment_methods,
                   wol.item_id,
                   wol.item_name,
                   wol.quantity,
                   wol.unit_price,
                   wol.line_total,
                   0 AS profit,
                   wo.status,
                   'online_store' AS channel
              FROM web_order_lines wol
              JOIN web_orders wo ON wo.id = wol.order_id
             WHERE wo.business_id = ?
               AND CAST(wo.created_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR wo.catalog_branch_id = ?)
          ORDER BY wo.created_at DESC
             LIMIT 500
            """;

    private static final String Q_PAYMENT_METHODS = """
            SELECT sp.method,
                   COUNT(DISTINCT s.id) AS txn_count,
                   COALESCE(SUM(sp.amount), 0) AS total_amount
              FROM sale_payments sp
              JOIN sales s ON s.id = sp.sale_id
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR s.branch_id = ?)
          GROUP BY sp.method
          ORDER BY total_amount DESC
            """;

    private static final String Q_STAFF_PERFORMANCE = """
            SELECT s.sold_by AS user_id,
                   COALESCE(NULLIF(TRIM(u.name), ''), u.email, s.sold_by) AS user_name,
                   COUNT(DISTINCT s.id) AS sale_count,
                   COALESCE(SUM(sil.quantity), 0) AS item_count,
                   COALESCE(SUM(sil.line_total), 0) AS total_revenue,
                   COALESCE(SUM(sil.profit), 0) AS total_profit
              FROM sales s
              JOIN sale_items sil ON sil.sale_id = s.id
         LEFT JOIN users u ON u.id = s.sold_by AND u.business_id = s.business_id AND u.deleted_at IS NULL
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR s.branch_id = ?)
          GROUP BY s.sold_by, COALESCE(NULLIF(TRIM(u.name), ''), u.email, s.sold_by)
          ORDER BY total_revenue DESC
            """;

    @Transactional(readOnly = true)
    public List<RecentSaleRow> recentSales(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String branchId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String branchFilter = (branchId != null && !branchId.isBlank()) ? branchId : null;

        List<RecentSaleRow> out = new ArrayList<>();
        jdbc.query(
                Q_RECENT_SALES,
                rs -> {
                    out.add(new RecentSaleRow(
                            rs.getString("sale_id"),
                            rs.getTimestamp("sold_at").toInstant(),
                            rs.getString("cashier_name"),
                            rs.getString("customer_name"),
                            rs.getString("payment_method"),
                            rs.getString("payment_methods"),
                            rs.getString("item_id"),
                            rs.getString("item_name"),
                            rs.getBigDecimal("quantity").setScale(4, RoundingMode.HALF_UP),
                            rs.getBigDecimal("unit_price").setScale(4, RoundingMode.HALF_UP),
                            rs.getBigDecimal("line_total").setScale(2, RoundingMode.HALF_UP),
                            rs.getBigDecimal("profit").setScale(2, RoundingMode.HALF_UP),
                            rs.getString("status"),
                            rs.getString("channel")
                    ));
                },
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                from,
                to,
                branchFilter,
                branchFilter);
        return out;
    }

    @Transactional(readOnly = true)
    public List<RecentSaleRow> recentWebOrderLines(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String branchId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String branchFilter = (branchId != null && !branchId.isBlank()) ? branchId : null;

        List<RecentSaleRow> out = new ArrayList<>();
        jdbc.query(
                Q_RECENT_WEB_ORDER_LINES,
                rs -> {
                    out.add(new RecentSaleRow(
                            rs.getString("sale_id"),
                            rs.getTimestamp("sold_at").toInstant(),
                            rs.getString("cashier_name"),
                            rs.getString("customer_name"),
                            rs.getString("payment_method"),
                            rs.getString("payment_methods"),
                            rs.getString("item_id"),
                            rs.getString("item_name"),
                            rs.getBigDecimal("quantity").setScale(4, RoundingMode.HALF_UP),
                            rs.getBigDecimal("unit_price").setScale(4, RoundingMode.HALF_UP),
                            rs.getBigDecimal("line_total").setScale(2, RoundingMode.HALF_UP),
                            rs.getBigDecimal("profit").setScale(2, RoundingMode.HALF_UP),
                            rs.getString("status"),
                            rs.getString("channel")
                    ));
                },
                businessId,
                from,
                to,
                branchFilter,
                branchFilter);
        return out;
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodBreakdownRow> paymentsByMethod(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String branchId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String branchFilter = (branchId != null && !branchId.isBlank()) ? branchId : null;

        List<PaymentMethodBreakdownRow> out = new ArrayList<>();
        jdbc.query(
                Q_PAYMENT_METHODS,
                rs -> {
                    out.add(new PaymentMethodBreakdownRow(
                            rs.getString("method"),
                            rs.getLong("txn_count"),
                            rs.getBigDecimal("total_amount").setScale(2, RoundingMode.HALF_UP)
                    ));
                },
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                from,
                to,
                branchFilter,
                branchFilter);
        return out;
    }

    @Transactional(readOnly = true)
    public List<StaffPerformanceRow> staffPerformance(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String branchId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String branchFilter = (branchId != null && !branchId.isBlank()) ? branchId : null;

        List<StaffPerformanceRow> out = new ArrayList<>();
        jdbc.query(
                Q_STAFF_PERFORMANCE,
                rs -> {
                    out.add(new StaffPerformanceRow(
                            rs.getString("user_id"),
                            rs.getString("user_name"),
                            rs.getLong("sale_count"),
                            rs.getLong("item_count"),
                            rs.getBigDecimal("total_revenue").setScale(2, RoundingMode.HALF_UP),
                            rs.getBigDecimal("total_profit").setScale(2, RoundingMode.HALF_UP)
                    ));
                },
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                from,
                to,
                branchFilter,
                branchFilter);
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

    private static Agg combineAgg(Agg a, Agg b) {
        return new Agg(
                a.name != null ? a.name : b.name,
                a.gross.add(b.gross),
                a.refunds.add(b.refunds),
                a.profitGross.add(b.profitGross),
                a.profitRefunds.add(b.profitRefunds));
    }

    private static DailyAgg combineDaily(DailyAgg a, DailyAgg b) {
        return new DailyAgg(
                a.gross.add(b.gross),
                a.refunds.add(b.refunds),
                a.profitGross.add(b.profitGross),
                a.profitRefunds.add(b.profitRefunds));
    }

    private static ItemAgg combineItem(ItemAgg a, ItemAgg b) {
        return new ItemAgg(
                a.name != null ? a.name : b.name,
                a.sku != null ? a.sku : b.sku,
                a.qty.add(b.qty),
                a.gross.add(b.gross),
                a.refunds.add(b.refunds),
                a.profitGross.add(b.profitGross),
                a.profitRefunds.add(b.profitRefunds));
    }

    private static final class Agg {
        final String name;
        final BigDecimal gross;
        final BigDecimal refunds;
        final BigDecimal profitGross;
        final BigDecimal profitRefunds;

        Agg(String name, BigDecimal gross, BigDecimal refunds, BigDecimal profitGross, BigDecimal profitRefunds) {
            this.name = name;
            this.gross = gross;
            this.refunds = refunds;
            this.profitGross = profitGross;
            this.profitRefunds = profitRefunds;
        }
    }

    private static final class DailyAgg {
        final BigDecimal gross;
        final BigDecimal refunds;
        final BigDecimal profitGross;
        final BigDecimal profitRefunds;

        DailyAgg(BigDecimal gross, BigDecimal refunds, BigDecimal profitGross, BigDecimal profitRefunds) {
            this.gross = gross;
            this.refunds = refunds;
            this.profitGross = profitGross;
            this.profitRefunds = profitRefunds;
        }
    }

    private static final class ItemAgg {
        final String name;
        final String sku;
        final BigDecimal qty;
        final BigDecimal gross;
        final BigDecimal refunds;
        final BigDecimal profitGross;
        final BigDecimal profitRefunds;

        ItemAgg(String name, String sku, BigDecimal qty, BigDecimal gross, BigDecimal refunds,
                BigDecimal profitGross, BigDecimal profitRefunds) {
            this.name = name;
            this.sku = sku;
            this.qty = qty;
            this.gross = gross;
            this.refunds = refunds;
            this.profitGross = profitGross;
            this.profitRefunds = profitRefunds;
        }
    }
}
