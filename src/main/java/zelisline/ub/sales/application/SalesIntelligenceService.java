package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;
import zelisline.ub.reporting.repository.MvSalesDailyRepository.ItemDailyRollup;
import zelisline.ub.reporting.repository.MvSalesDailyRepository.ItemDayQtyRevenue;
import zelisline.ub.reporting.repository.MvSalesDailyRepository.ItemVelocityPast;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.CategoryDailyRevenueRow;
import zelisline.ub.sales.api.dto.ItemActivityResponse;
import zelisline.ub.sales.api.dto.ItemActivitySummary;
import zelisline.ub.sales.api.dto.ItemDailySalesRow;
import zelisline.ub.sales.api.dto.ItemPeriodBuckets;
import zelisline.ub.sales.api.dto.ItemRevenueRow;
import zelisline.ub.sales.api.dto.ItemStockInRow;
import zelisline.ub.sales.api.dto.ItemVelocityRow;
import zelisline.ub.sales.api.dto.PaymentLedgerRow;
import zelisline.ub.sales.api.dto.PaymentMethodBreakdownRow;
import zelisline.ub.sales.api.dto.RecentSaleRow;
import zelisline.ub.sales.api.dto.RevenueByCategoryRow;
import zelisline.ub.sales.api.dto.StaffPerformanceRow;
import zelisline.ub.sales.application.ItemVelocityMerge.ItemMeta;
import zelisline.ub.sales.application.ItemVelocityMerge.PastBuckets;
import zelisline.ub.sales.application.ItemVelocityMerge.TodayTotals;

@Service
@RequiredArgsConstructor
public class SalesIntelligenceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal QTY_ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final int DEFAULT_VELOCITY_LIMIT = 100;
    private static final int MAX_VELOCITY_LIMIT = 500;
    private static final int STOCK_IN_LIMIT = 40;
    private static final int ITEM_RECENT_SALES_LIMIT = 80;
    private static final List<String> STOCK_IN_TYPES = List.of(
            PurchasingConstants.MOVEMENT_RECEIPT,
            InventoryConstants.MOVEMENT_OPENING,
            InventoryConstants.MOVEMENT_TRANSFER_IN
    );

    private final JdbcTemplate jdbc;
    private final MvSalesDailyRepository mvSalesDailyRepository;
    private final ItemRepository itemRepository;
    private final StockMovementRepository stockMovementRepository;

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
               AND (? IS NULL OR s.branch_id = ?)
               AND (? IS NULL OR i.item_type_id = ?)
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
               AND (? IS NULL OR s.branch_id = ?)
               AND (? IS NULL OR i.item_type_id = ?)
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
            String categoryId,
            String branchId,
            String itemTypeId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String catFilter = (categoryId != null && !categoryId.isBlank()) ? categoryId : null;
        String branchFilter = (branchId != null && !branchId.isBlank()) ? branchId : null;
        String typeFilter = blankToNull(itemTypeId);

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
                catFilter,
                branchFilter,
                branchFilter,
                typeFilter,
                typeFilter);

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
                catFilter,
                branchFilter,
                branchFilter,
                typeFilter,
                typeFilter);

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
                   s.receipt_no,
                   s.sold_at,
                   COALESCE(NULLIF(TRIM(u.name), ''), u.email, s.sold_by) AS cashier_name,
                   COALESCE(cu.name, '') AS customer_name,
                   (SELECT CASE
                        WHEN COUNT(DISTINCT sp2.method) > 1 THEN 'split'
                        ELSE COALESCE(MAX(sp2.method), 'unknown')
                    END
                      FROM sale_payments sp2
                     WHERE sp2.sale_id = s.id) AS payment_method,
                   (SELECT GROUP_CONCAT(DISTINCT sp2.method ORDER BY sp2.method SEPARATOR ',')
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
               AND (? IS NULL OR i.item_type_id = ?)
               AND (? IS NULL OR sil.item_id = ?)
          ORDER BY s.sold_at DESC
             LIMIT ?
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
                   COALESCE(SUM(
                     CASE
                       WHEN ? IS NULL THEN sp.amount
                       ELSE sp.amount * (
                         SELECT COALESCE(SUM(sil.line_total), 0)
                           FROM sale_items sil
                           JOIN items i ON i.id = sil.item_id
                                          AND i.business_id = s.business_id
                                          AND i.deleted_at IS NULL
                          WHERE sil.sale_id = s.id
                            AND i.item_type_id = ?
                       ) / NULLIF((
                         SELECT COALESCE(SUM(sil2.line_total), 0)
                           FROM sale_items sil2
                          WHERE sil2.sale_id = s.id
                       ), 0)
                     END
                   ), 0) AS total_amount
              FROM sale_payments sp
              JOIN sales s ON s.id = sp.sale_id
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR s.branch_id = ?)
               AND (? IS NULL OR EXISTS (
                     SELECT 1
                       FROM sale_items sil
                       JOIN items i ON i.id = sil.item_id
                                      AND i.business_id = s.business_id
                                      AND i.deleted_at IS NULL
                      WHERE sil.sale_id = s.id
                        AND i.item_type_id = ?
                   ))
          GROUP BY sp.method
          ORDER BY total_amount DESC
            """;

    /** Chronological tender lines for a day (or short range) — one row per sale_payments. */
    private static final String Q_PAYMENT_LEDGER = """
            SELECT sp.id AS payment_id,
                   sp.sale_id,
                   s.receipt_no,
                   s.sold_at,
                   sp.method,
                   sp.amount,
                   sp.reference,
                   sp.sort_order,
                   s.status,
                   s.branch_id,
                   COALESCE(NULLIF(TRIM(u.name), ''), u.email, s.sold_by) AS cashier_name,
                   COALESCE(cu.name, '') AS customer_name,
                   s.grand_total
              FROM sale_payments sp
              JOIN sales s ON s.id = sp.sale_id
         LEFT JOIN users u ON u.id = s.sold_by AND u.business_id = s.business_id AND u.deleted_at IS NULL
         LEFT JOIN customers cu ON cu.id = s.customer_id AND cu.business_id = s.business_id
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR s.branch_id = ?)
          ORDER BY s.sold_at ASC, sp.sort_order ASC
             LIMIT 3000
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
              JOIN items i ON i.id = sil.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
         LEFT JOIN users u ON u.id = s.sold_by AND u.business_id = s.business_id AND u.deleted_at IS NULL
             WHERE s.business_id = ?
               AND s.status IN (?, ?)
               AND CAST(s.sold_at AS DATE) BETWEEN ? AND ?
               AND (? IS NULL OR s.branch_id = ?)
               AND (? IS NULL OR i.item_type_id = ?)
          GROUP BY s.sold_by, COALESCE(NULLIF(TRIM(u.name), ''), u.email, s.sold_by)
          ORDER BY total_revenue DESC
            """;

    @Transactional(readOnly = true)
    public List<RecentSaleRow> recentSales(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String branchId,
            String itemTypeId
    ) {
        return recentSales(businessId, fromInclusive, toInclusive, branchId, itemTypeId, null, 500);
    }

    @Transactional(readOnly = true)
    public List<RecentSaleRow> recentSales(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String branchId,
            String itemTypeId,
            String itemId,
            int limit
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String branchFilter = (branchId != null && !branchId.isBlank()) ? branchId : null;
        String typeFilter = blankToNull(itemTypeId);
        String itemFilter = blankToNull(itemId);
        int rowLimit = Math.max(1, Math.min(limit, 500));

        List<RecentSaleRow> out = new ArrayList<>();
        jdbc.query(
                Q_RECENT_SALES,
                rs -> {
                    long receiptNo = rs.getLong("receipt_no");
                    out.add(new RecentSaleRow(
                            rs.getString("sale_id"),
                            rs.wasNull() ? null : receiptNo,
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
                branchFilter,
                typeFilter,
                typeFilter,
                itemFilter,
                itemFilter,
                rowLimit);
        return out;
    }

    @Transactional(readOnly = true)
    public List<ItemVelocityRow> itemVelocity(
            String businessId,
            String branchId,
            String itemTypeId,
            Integer limit
    ) {
        int rowLimit = limit == null ? DEFAULT_VELOCITY_LIMIT : Math.max(1, Math.min(limit, MAX_VELOCITY_LIMIT));
        String branchFilter = blankToNull(branchId);
        String typeFilter = blankToNull(itemTypeId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate yesterday = today.minusDays(1);
        LocalDate last3From = today.minusDays(2);
        LocalDate last7From = today.minusDays(6);
        LocalDate last30From = today.minusDays(29);

        List<PastBuckets> past = new ArrayList<>();
        for (ItemVelocityPast row : mvSalesDailyRepository.itemVelocityPast(
                businessId,
                today,
                yesterday,
                last3From,
                last7From,
                last30From,
                branchFilter,
                typeFilter,
                rowLimit
        )) {
            past.add(new PastBuckets(
                    row.getItemId(),
                    row.getItemName(),
                    row.getSku(),
                    row.getCurrentStock(),
                    row.getYesterdayQty(),
                    row.getYesterdayRevenue(),
                    row.getLast3PastQty(),
                    row.getLast3PastRevenue(),
                    row.getLast7PastQty(),
                    row.getLast7PastRevenue(),
                    row.getLast30PastQty(),
                    row.getLast30PastRevenue()
            ));
        }

        Map<String, TodayTotals> todayByItem = new HashMap<>();
        for (ItemDayQtyRevenue row : mvSalesDailyRepository.sumOltpByItemForDay(
                businessId, today, branchFilter, typeFilter)) {
            todayByItem.put(row.getItemId(), new TodayTotals(
                    qtyOrZero(row.getQty()),
                    moneyOrZero(row.getRevenue())
            ));
        }

        Map<String, ItemMeta> todayOnlyMeta = loadItemMeta(businessId, todayByItem.keySet(), past);
        return ItemVelocityMerge.merge(past, todayByItem, todayOnlyMeta, rowLimit);
    }

    @Transactional(readOnly = true)
    public ItemActivityResponse itemActivity(
            String businessId,
            String itemId,
            String branchId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        String branchFilter = blankToNull(branchId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate[] seriesWindow = resolveWindow(
                fromInclusive != null ? fromInclusive : today.minusDays(29),
                toInclusive != null ? toInclusive : today
        );
        LocalDate seriesFrom = seriesWindow[0];
        LocalDate seriesTo = seriesWindow[1];

        ItemPeriodBuckets periods = periodsForItem(businessId, itemId, branchFilter, today);
        List<ItemDailySalesRow> daily = dailyForItem(businessId, itemId, branchFilter, seriesFrom, seriesTo, today);

        List<StockMovement> inbound = stockMovementRepository.findInboundForItem(
                businessId,
                itemId,
                branchFilter,
                STOCK_IN_TYPES,
                PageRequest.of(0, STOCK_IN_LIMIT)
        );
        List<ItemStockInRow> stockIns = inbound.stream()
                .map(m -> new ItemStockInRow(
                        m.getId(),
                        m.getMovementType(),
                        qtyOrZero(m.getQuantityDelta()),
                        m.getBranchId(),
                        m.getReason(),
                        m.getNotes(),
                        m.getCreatedAt()
                ))
                .toList();

        Instant lastReceiptAt = null;
        BigDecimal lastReceiptQty = null;
        if (!inbound.isEmpty()) {
            StockMovement last = inbound.get(0);
            lastReceiptAt = last.getCreatedAt();
            lastReceiptQty = qtyOrZero(last.getQuantityDelta());
        }

        BigDecimal soldSinceLastReceipt = QTY_ZERO;
        BigDecimal sellThroughPct = null;
        if (lastReceiptAt != null && lastReceiptQty != null && lastReceiptQty.compareTo(BigDecimal.ZERO) > 0) {
            soldSinceLastReceipt = qtySoldSince(businessId, itemId, branchFilter, lastReceiptAt);
            sellThroughPct = soldSinceLastReceipt
                    .multiply(BigDecimal.valueOf(100))
                    .divide(lastReceiptQty, 1, RoundingMode.HALF_UP);
        }

        BigDecimal avgUnitsPerDay7d = periods.last7Qty()
                .divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);

        ItemActivitySummary summary = new ItemActivitySummary(
                item.getId(),
                item.getName(),
                item.getSku(),
                qtyOrZero(item.getCurrentStock()),
                lastReceiptAt,
                lastReceiptQty,
                soldSinceLastReceipt,
                sellThroughPct,
                avgUnitsPerDay7d
        );

        List<RecentSaleRow> recent = recentSales(
                businessId,
                seriesFrom,
                seriesTo,
                branchFilter,
                null,
                itemId,
                ITEM_RECENT_SALES_LIMIT
        );

        return new ItemActivityResponse(summary, periods, daily, stockIns, recent);
    }

    private ItemPeriodBuckets periodsForItem(
            String businessId,
            String itemId,
            String branchFilter,
            LocalDate today
    ) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate last3From = today.minusDays(2);
        LocalDate last7From = today.minusDays(6);
        LocalDate last30From = today.minusDays(29);

        Map<LocalDate, ItemDailyRollup> byDay = new HashMap<>();
        for (ItemDailyRollup row : mvSalesDailyRepository.sumByDayForItem(
                businessId, itemId, last30From, yesterday, branchFilter)) {
            byDay.put(row.getBusinessDay(), row);
        }
        for (ItemDailyRollup row : mvSalesDailyRepository.sumOltpByDayForItem(
                businessId, itemId, last30From, yesterday, branchFilter)) {
            byDay.putIfAbsent(row.getBusinessDay(), row);
        }

        TodayTotals todayTotals = TodayTotals.zero();
        for (ItemDailyRollup row : mvSalesDailyRepository.sumOltpForItemDay(
                businessId, itemId, today, branchFilter)) {
            todayTotals = new TodayTotals(qtyOrZero(row.getQty()), moneyOrZero(row.getRevenue()));
        }

        BigDecimal yesterdayQty = dayQty(byDay, yesterday);
        BigDecimal yesterdayRev = dayRev(byDay, yesterday);
        BigDecimal last3Past = sumQty(byDay, last3From, yesterday);
        BigDecimal last3PastRev = sumRev(byDay, last3From, yesterday);
        BigDecimal last7Past = sumQty(byDay, last7From, yesterday);
        BigDecimal last7PastRev = sumRev(byDay, last7From, yesterday);
        BigDecimal last30Past = sumQty(byDay, last30From, yesterday);
        BigDecimal last30PastRev = sumRev(byDay, last30From, yesterday);

        return new ItemPeriodBuckets(
                todayTotals.qty(),
                todayTotals.revenue(),
                yesterdayQty,
                yesterdayRev,
                last3Past.add(todayTotals.qty()),
                last3PastRev.add(todayTotals.revenue()),
                last7Past.add(todayTotals.qty()),
                last7PastRev.add(todayTotals.revenue()),
                last30Past.add(todayTotals.qty()),
                last30PastRev.add(todayTotals.revenue())
        );
    }

    private List<ItemDailySalesRow> dailyForItem(
            String businessId,
            String itemId,
            String branchFilter,
            LocalDate from,
            LocalDate to,
            LocalDate today
    ) {
        LocalDate mvUpper = to.isBefore(today) ? to : today.minusDays(1);
        Map<LocalDate, ItemDailyRollup> byDay = new HashMap<>();
        if (!mvUpper.isBefore(from)) {
            for (ItemDailyRollup row : mvSalesDailyRepository.sumByDayForItem(
                    businessId, itemId, from, mvUpper, branchFilter)) {
                byDay.put(row.getBusinessDay(), row);
            }
            for (ItemDailyRollup row : mvSalesDailyRepository.sumOltpByDayForItem(
                    businessId, itemId, from, mvUpper, branchFilter)) {
                byDay.putIfAbsent(row.getBusinessDay(), row);
            }
        }
        if (!today.isBefore(from) && !today.isAfter(to)) {
            for (ItemDailyRollup row : mvSalesDailyRepository.sumOltpForItemDay(
                    businessId, itemId, today, branchFilter)) {
                byDay.put(row.getBusinessDay(), row);
            }
        }

        List<ItemDailySalesRow> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            ItemDailyRollup row = byDay.get(d);
            if (row == null) {
                out.add(new ItemDailySalesRow(d, QTY_ZERO, ZERO, ZERO, ZERO));
            } else {
                out.add(new ItemDailySalesRow(
                        d,
                        qtyOrZero(row.getQty()),
                        moneyOrZero(row.getRevenue()),
                        moneyOrZero(row.getCost()),
                        moneyOrZero(row.getProfit())
                ));
            }
        }
        return out;
    }

    private BigDecimal qtySoldSince(
            String businessId,
            String itemId,
            String branchFilter,
            Instant since
    ) {
        String sql = """
                SELECT COALESCE(SUM(si.quantity), 0)
                  FROM sale_items si
                  JOIN sales s ON s.id = si.sale_id
                 WHERE s.business_id = ?
                   AND si.item_id = ?
                   AND s.status = ?
                   AND s.sold_at >= ?
                   AND (? IS NULL OR s.branch_id = ?)
                """;
        BigDecimal qty = jdbc.queryForObject(
                sql,
                BigDecimal.class,
                businessId,
                itemId,
                SalesConstants.SALE_STATUS_COMPLETED,
                java.sql.Timestamp.from(since),
                branchFilter,
                branchFilter
        );
        return qtyOrZero(qty);
    }

    private Map<String, ItemMeta> loadItemMeta(
            String businessId,
            Set<String> todayItemIds,
            List<PastBuckets> past
    ) {
        Set<String> known = new HashSet<>();
        for (PastBuckets p : past) {
            known.add(p.itemId());
        }
        Set<String> missing = new HashSet<>();
        for (String id : todayItemIds) {
            if (!known.contains(id)) {
                missing.add(id);
            }
        }
        Map<String, ItemMeta> meta = new HashMap<>();
        if (missing.isEmpty()) {
            return meta;
        }
        for (Item item : itemRepository.findAllById(missing)) {
            if (!businessId.equals(item.getBusinessId()) || item.getDeletedAt() != null) {
                continue;
            }
            meta.put(item.getId(), new ItemMeta(
                    item.getId(),
                    item.getName(),
                    item.getSku(),
                    qtyOrZero(item.getCurrentStock())
            ));
        }
        return meta;
    }

    private static BigDecimal dayQty(Map<LocalDate, ItemDailyRollup> byDay, LocalDate day) {
        ItemDailyRollup row = byDay.get(day);
        return row == null ? QTY_ZERO : qtyOrZero(row.getQty());
    }

    private static BigDecimal dayRev(Map<LocalDate, ItemDailyRollup> byDay, LocalDate day) {
        ItemDailyRollup row = byDay.get(day);
        return row == null ? ZERO : moneyOrZero(row.getRevenue());
    }

    private static BigDecimal sumQty(Map<LocalDate, ItemDailyRollup> byDay, LocalDate from, LocalDate to) {
        BigDecimal total = QTY_ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            total = total.add(dayQty(byDay, d));
        }
        return total;
    }

    private static BigDecimal sumRev(Map<LocalDate, ItemDailyRollup> byDay, LocalDate from, LocalDate to) {
        BigDecimal total = ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            total = total.add(dayRev(byDay, d));
        }
        return total;
    }

    private static BigDecimal qtyOrZero(BigDecimal v) {
        return v == null ? QTY_ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal moneyOrZero(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
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
                            null,
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
            String branchId,
            String itemTypeId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String branchFilter = (branchId != null && !branchId.isBlank()) ? branchId : null;
        String typeFilter = blankToNull(itemTypeId);

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
                typeFilter,
                typeFilter,
                businessId,
                SalesConstants.SALE_STATUS_COMPLETED,
                SalesConstants.SALE_STATUS_REFUNDED,
                from,
                to,
                branchFilter,
                branchFilter,
                typeFilter,
                typeFilter);
        return out;
    }

    @Transactional(readOnly = true)
    public List<PaymentLedgerRow> paymentLedger(
            String businessId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            String branchId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String branchFilter = (branchId != null && !branchId.isBlank()) ? branchId : null;

        List<PaymentLedgerRow> out = new ArrayList<>();
        jdbc.query(
                Q_PAYMENT_LEDGER,
                rs -> {
                    long receiptNoRaw = rs.getLong("receipt_no");
                    Long receiptNo = rs.wasNull() ? null : receiptNoRaw;
                    out.add(new PaymentLedgerRow(
                            rs.getString("payment_id"),
                            rs.getString("sale_id"),
                            receiptNo,
                            rs.getTimestamp("sold_at").toInstant(),
                            rs.getString("method"),
                            rs.getBigDecimal("amount").setScale(2, RoundingMode.HALF_UP),
                            rs.getString("reference"),
                            rs.getInt("sort_order"),
                            rs.getString("status"),
                            rs.getString("branch_id"),
                            rs.getString("cashier_name"),
                            rs.getString("customer_name"),
                            rs.getBigDecimal("grand_total").setScale(2, RoundingMode.HALF_UP)
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
            String branchId,
            String itemTypeId
    ) {
        LocalDate[] w = resolveWindow(fromInclusive, toInclusive);
        Date from = Date.valueOf(w[0]);
        Date to = Date.valueOf(w[1]);
        String branchFilter = (branchId != null && !branchId.isBlank()) ? branchId : null;
        String typeFilter = blankToNull(itemTypeId);

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
                branchFilter,
                typeFilter,
                typeFilter);
        return out;
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
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
