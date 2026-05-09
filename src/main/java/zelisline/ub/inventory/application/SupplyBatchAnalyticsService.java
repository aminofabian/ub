package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.analytics.BatchDashboardResponse;
import zelisline.ub.inventory.api.dto.analytics.BatchTableResponse;

@Service
@RequiredArgsConstructor
public class SupplyBatchAnalyticsService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public BatchDashboardResponse getDashboard(String businessId, String branchId, String from, String to) {
        var summary = buildSummary(businessId, branchId);
        var dailyTrend = buildDailyTrend(businessId, branchId, from, to);
        var statusDistribution = buildStatusDistribution(businessId, branchId);
        var topProducts = buildTopProducts(businessId, branchId);
        var expiringBatches = buildExpiringBatches(businessId, branchId);
        var lowStockProducts = buildLowStockProducts(businessId);
        var alerts = buildAlerts(summary, expiringBatches, dailyTrend);

        return new BatchDashboardResponse(
                summary, dailyTrend, statusDistribution, topProducts,
                expiringBatches, lowStockProducts, alerts
        );
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public BatchTableResponse getTable(
            String businessId,
            String branchId,
            String status,
            String search,
            String from,
            String to,
            String quantityMin,
            String quantityMax,
            int page,
            int size,
            String sortBy,
            String sortDir
    ) {
        String where = buildTableWhere(businessId, branchId, status, search, from, to, quantityMin, quantityMax);

        String countSql = """
                SELECT COUNT(*) FROM inventory_batches ib
                JOIN items i ON i.id = ib.item_id
                LEFT JOIN supply_batches sb ON sb.id = ib.supply_batch_id
                """ + where;
        Query countQ = entityManager.createNativeQuery(countSql);
        setTableParams(countQ, businessId, branchId, status, search, from, to, quantityMin, quantityMax);
        long total = ((Number) countQ.getSingleResult()).longValue();

        String orderCol = switch (sortBy != null ? sortBy : "receivedAt") {
            case "batchNumber" -> "ib.batch_number";
            case "itemName" -> "i.name";
            case "initialQuantity" -> "ib.initial_quantity";
            case "quantityRemaining" -> "ib.quantity_remaining";
            case "unitCost" -> "ib.unit_cost";
            case "expiryDate" -> "ib.expiry_date";
            case "status" -> "ib.status";
            default -> "ib.received_at";
        };
        String dir = "desc".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";

        String sql = """
                SELECT
                    ib.id,
                    ib.supply_batch_id,
                    ib.batch_number,
                    ib.item_id,
                    i.name as item_name,
                    i.sku as item_sku,
                    COALESCE(c.name, '') as category_name,
                    ib.branch_id,
                    ib.initial_quantity,
                    ib.quantity_remaining,
                    ib.unit_cost,
                    (ib.quantity_remaining * ib.unit_cost) as total_value,
                    ib.expiry_date,
                    ib.status,
                    ib.received_at,
                    COALESCE(sup.name, '') as supplier_name
                FROM inventory_batches ib
                JOIN items i ON i.id = ib.item_id AND i.business_id = :businessId
                LEFT JOIN categories c ON c.id = i.category_id
                LEFT JOIN supply_batches sb ON sb.id = ib.supply_batch_id
                LEFT JOIN suppliers sup ON sup.id = sb.supplier_id AND sup.business_id = :businessId
                """ + where + " ORDER BY " + orderCol + " " + dir + " LIMIT :limit OFFSET :offset";

        Query q = entityManager.createNativeQuery(sql);
        setTableParams(q, businessId, branchId, status, search, from, to, quantityMin, quantityMax);
        q.setParameter("limit", size);
        q.setParameter("offset", page * size);

        List<Object[]> raw = q.getResultList();
        List<BatchTableResponse.BatchTableRow> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(new BatchTableResponse.BatchTableRow(
                    str(r[0]), str(r[1]), str(r[2]), str(r[3]), str(r[4]), str(r[5]),
                    str(r[6]), str(r[7]), null,
                    bd(r[8]), bd(r[9]), bd(r[10]), bd(r[11]),
                    r[12] != null ? r[12].toString() : null,
                    str(r[13]), r[14] != null ? r[14].toString() : null,
                    str(r[15])
            ));
        }

        return new BatchTableResponse(rows, total, page, size);
    }

    private BatchDashboardResponse.BatchSummaryCards buildSummary(String businessId, String branchId) {
        String baseWhere = " WHERE ib.business_id = :businessId " + branchFilter(branchId, "ib");

        Long today = scalarLong("SELECT COUNT(*) FROM inventory_batches ib" + baseWhere + " AND DATE(ib.received_at) = CURDATE()", businessId, branchId);
        Long week = scalarLong("SELECT COUNT(*) FROM inventory_batches ib" + baseWhere + " AND ib.received_at >= DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY)", businessId, branchId);
        Long month = scalarLong("SELECT COUNT(*) FROM inventory_batches ib" + baseWhere + " AND ib.received_at >= DATE_FORMAT(CURDATE(), '%Y-%m-01')", businessId, branchId);
        Long active = scalarLong("SELECT COUNT(*) FROM inventory_batches ib" + baseWhere + " AND ib.status = 'active'", businessId, branchId);
        Long completed = scalarLong("SELECT COUNT(*) FROM inventory_batches ib" + baseWhere + " AND ib.status = 'depleted'", businessId, branchId);
        Long zeroQty = scalarLong("SELECT COUNT(*) FROM inventory_batches ib" + baseWhere + " AND ib.quantity_remaining = 0", businessId, branchId);
        Long lowQty = scalarLong("SELECT COUNT(*) FROM inventory_batches ib JOIN items i ON i.id = ib.item_id" + baseWhere + " AND ib.quantity_remaining > 0 AND ib.quantity_remaining <= COALESCE(i.reorder_level, 10)", businessId, branchId);
        Long expired = scalarLong("SELECT COUNT(*) FROM inventory_batches ib" + baseWhere + " AND ib.expiry_date IS NOT NULL AND ib.expiry_date < CURDATE()", businessId, branchId);

        BigDecimal totalUnits = scalarBd("SELECT COALESCE(SUM(ib.initial_quantity), 0) FROM inventory_batches ib" + baseWhere, businessId, branchId);
        BigDecimal totalCost = scalarBd("SELECT COALESCE(SUM(ib.initial_quantity * ib.unit_cost), 0) FROM inventory_batches ib" + baseWhere, businessId, branchId);
        BigDecimal stockValue = scalarBd("SELECT COALESCE(SUM(ib.quantity_remaining * ib.unit_cost), 0) FROM inventory_batches ib" + baseWhere, businessId, branchId);
        BigDecimal avgQty = scalarBd("SELECT COALESCE(AVG(ib.initial_quantity), 0) FROM inventory_batches ib" + baseWhere, businessId, branchId);

        return new BatchDashboardResponse.BatchSummaryCards(
                today != null ? today : 0L,
                week != null ? week : 0L,
                month != null ? month : 0L,
                active != null ? active : 0L,
                completed != null ? completed : 0L,
                zeroQty != null ? zeroQty : 0L,
                lowQty != null ? lowQty : 0L,
                expired != null ? expired : 0L,
                totalUnits, totalCost, stockValue, avgQty
        );
    }

    @SuppressWarnings("unchecked")
    private List<BatchDashboardResponse.BatchTrendPoint> buildDailyTrend(String businessId, String branchId, String from, String to) {
        String sql = """
                SELECT DATE(ib.received_at) as dt,
                       COUNT(*) as cnt,
                       COALESCE(SUM(ib.initial_quantity), 0) as qty,
                       COALESCE(SUM(ib.initial_quantity * ib.unit_cost), 0) as cost
                FROM inventory_batches ib
                WHERE ib.business_id = :businessId
                """ + branchFilter(branchId, "ib") + dateRangeFilter(from, to, "ib.received_at") + """
                GROUP BY DATE(ib.received_at)
                ORDER BY dt DESC
                LIMIT 90
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("businessId", businessId);
        if (branchId != null && !branchId.isBlank()) q.setParameter("branchId", branchId);
        if (from != null && !from.isBlank()) q.setParameter("fromDate", from);
        if (to != null && !to.isBlank()) q.setParameter("toDate", to);

        List<Object[]> raw = q.getResultList();
        List<BatchDashboardResponse.BatchTrendPoint> out = new ArrayList<>();
        for (Object[] r : raw) {
            out.add(new BatchDashboardResponse.BatchTrendPoint(
                    r[0].toString(),
                    ((Number) r[1]).longValue(),
                    bd(r[2]),
                    bd(r[3])
            ));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<BatchDashboardResponse.StatusDistributionPoint> buildStatusDistribution(String businessId, String branchId) {
        String sql = """
                SELECT ib.status, COUNT(*) as cnt
                FROM inventory_batches ib
                WHERE ib.business_id = :businessId
                """ + branchFilter(branchId, "ib") + """
                GROUP BY ib.status
                ORDER BY cnt DESC
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("businessId", businessId);
        if (branchId != null && !branchId.isBlank()) q.setParameter("branchId", branchId);

        List<Object[]> raw = q.getResultList();
        List<BatchDashboardResponse.StatusDistributionPoint> out = new ArrayList<>();
        for (Object[] r : raw) {
            out.add(new BatchDashboardResponse.StatusDistributionPoint(str(r[0]), ((Number) r[1]).longValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<BatchDashboardResponse.TopProductPoint> buildTopProducts(String businessId, String branchId) {
        String sql = """
                SELECT ib.item_id, i.name, i.sku,
                       COUNT(*) as batch_count,
                       COALESCE(SUM(ib.initial_quantity), 0) as total_qty,
                       COALESCE(SUM(ib.initial_quantity * ib.unit_cost), 0) as total_val
                FROM inventory_batches ib
                JOIN items i ON i.id = ib.item_id AND i.business_id = :businessId
                WHERE ib.business_id = :businessId
                """ + branchFilter(branchId, "ib") + """
                GROUP BY ib.item_id, i.name, i.sku
                ORDER BY batch_count DESC
                LIMIT 10
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("businessId", businessId);
        if (branchId != null && !branchId.isBlank()) q.setParameter("branchId", branchId);

        List<Object[]> raw = q.getResultList();
        List<BatchDashboardResponse.TopProductPoint> out = new ArrayList<>();
        for (Object[] r : raw) {
            out.add(new BatchDashboardResponse.TopProductPoint(
                    str(r[0]), str(r[1]), str(r[2]),
                    ((Number) r[3]).longValue(), bd(r[4]), bd(r[5])
            ));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<BatchDashboardResponse.ExpiringBatchPoint> buildExpiringBatches(String businessId, String branchId) {
        String sql = """
                SELECT ib.id, ib.supply_batch_id, ib.batch_number, ib.item_id, i.name,
                       ib.quantity_remaining, ib.expiry_date,
                       DATEDIFF(ib.expiry_date, CURDATE()) as days_left
                FROM inventory_batches ib
                JOIN items i ON i.id = ib.item_id AND i.business_id = :businessId
                WHERE ib.business_id = :businessId
                """ + branchFilter(branchId, "ib") + """
                  AND ib.expiry_date IS NOT NULL
                  AND ib.expiry_date <= DATE_ADD(CURDATE(), INTERVAL 14 DAY)
                  AND ib.quantity_remaining > 0
                ORDER BY ib.expiry_date ASC
                LIMIT 20
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("businessId", businessId);
        if (branchId != null && !branchId.isBlank()) q.setParameter("branchId", branchId);

        List<Object[]> raw = q.getResultList();
        List<BatchDashboardResponse.ExpiringBatchPoint> out = new ArrayList<>();
        for (Object[] r : raw) {
            out.add(new BatchDashboardResponse.ExpiringBatchPoint(
                    str(r[0]), str(r[1]), str(r[2]), str(r[3]), str(r[4]),
                    bd(r[5]), r[6] != null ? r[6].toString() : null,
                    r[7] != null ? ((Number) r[7]).intValue() : 0
            ));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<BatchDashboardResponse.LowStockProductPoint> buildLowStockProducts(String businessId) {
        String sql = """
                SELECT i.id, i.name, i.sku, i.current_stock, i.reorder_level, COALESCE(c.name, '')
                FROM items i
                LEFT JOIN categories c ON c.id = i.category_id
                WHERE i.business_id = :businessId
                  AND i.is_stocked = 1
                  AND i.active = 1
                  AND i.deleted_at IS NULL
                  AND (i.current_stock <= COALESCE(i.reorder_level, 0) OR i.current_stock = 0)
                ORDER BY i.current_stock ASC
                LIMIT 20
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("businessId", businessId);

        List<Object[]> raw = q.getResultList();
        List<BatchDashboardResponse.LowStockProductPoint> out = new ArrayList<>();
        for (Object[] r : raw) {
            out.add(new BatchDashboardResponse.LowStockProductPoint(
                    str(r[0]), str(r[1]), str(r[2]), bd(r[3]), bd(r[4]), str(r[5])
            ));
        }
        return out;
    }

    private List<BatchDashboardResponse.BatchAlert> buildAlerts(
            BatchDashboardResponse.BatchSummaryCards summary,
            List<BatchDashboardResponse.ExpiringBatchPoint> expiring,
            List<BatchDashboardResponse.BatchTrendPoint> trend
    ) {
        List<BatchDashboardResponse.BatchAlert> alerts = new ArrayList<>();

        if (summary.totalBatchesToday() > 0) {
            alerts.add(new BatchDashboardResponse.BatchAlert("info",
                    summary.totalBatchesToday() + " batch" + (summary.totalBatchesToday() == 1 ? "" : "es") + " created today.",
                    summary.totalBatchesToday()));
        }
        if (summary.zeroQuantityBatches() > 0) {
            alerts.add(new BatchDashboardResponse.BatchAlert("warning",
                    summary.zeroQuantityBatches() + " batch" + (summary.zeroQuantityBatches() == 1 ? "" : "es") + " have zero quantity and require attention.",
                    summary.zeroQuantityBatches()));
        }
        if (summary.lowQuantityBatches() > 0) {
            alerts.add(new BatchDashboardResponse.BatchAlert("warning",
                    summary.lowQuantityBatches() + " batch" + (summary.lowQuantityBatches() == 1 ? "" : "es") + " have low quantity.",
                    summary.lowQuantityBatches()));
        }
        if (!expiring.isEmpty()) {
            long overdue = expiring.stream().filter(e -> e.daysUntilExpiry() < 0).count();
            long soon = expiring.stream().filter(e -> e.daysUntilExpiry() >= 0).count();
            if (overdue > 0) {
                alerts.add(new BatchDashboardResponse.BatchAlert("danger",
                        overdue + " batch" + (overdue == 1 ? "" : "es") + " are overdue/expired.", overdue));
            }
            if (soon > 0) {
                alerts.add(new BatchDashboardResponse.BatchAlert("warning",
                        soon + " batch" + (soon == 1 ? "" : "es") + " expire within 14 days.", soon));
            }
        }

        if (trend.size() >= 2) {
            BigDecimal lastWeek = trend.stream()
                    .limit(7)
                    .map(BatchDashboardResponse.BatchTrendPoint::quantityProduced)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal prevWeek = trend.stream()
                    .skip(7)
                    .limit(7)
                    .map(BatchDashboardResponse.BatchTrendPoint::quantityProduced)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (prevWeek.signum() > 0) {
                BigDecimal change = lastWeek.subtract(prevWeek)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(prevWeek, 1, java.math.RoundingMode.HALF_UP);
                if (change.abs().compareTo(BigDecimal.ONE) > 0) {
                    String dir = change.signum() >= 0 ? "increased" : "decreased";
                    alerts.add(new BatchDashboardResponse.BatchAlert("info",
                            "Production " + dir + " by " + change.abs().toPlainString() + "% compared to last week.", null));
                }
            }
        }

        return alerts;
    }

    // --- helpers ---

    private String buildTableWhere(String businessId, String branchId, String status, String search,
                                    String from, String to, String quantityMin, String quantityMax) {
        StringBuilder sb = new StringBuilder();
        sb.append(" WHERE ib.business_id = :businessId ");
        if (branchId != null && !branchId.isBlank()) sb.append(" AND ib.branch_id = :branchId ");
        if (status != null && !status.isBlank()) sb.append(" AND ib.status = :status ");
        if (search != null && !search.isBlank()) sb.append(" AND (i.name LIKE :search OR i.sku LIKE :search OR ib.batch_number LIKE :search) ");
        if (from != null && !from.isBlank()) sb.append(" AND DATE(ib.received_at) >= :fromDate ");
        if (to != null && !to.isBlank()) sb.append(" AND DATE(ib.received_at) <= :toDate ");
        if (quantityMin != null && !quantityMin.isBlank()) sb.append(" AND ib.quantity_remaining >= :qtyMin ");
        if (quantityMax != null && !quantityMax.isBlank()) sb.append(" AND ib.quantity_remaining <= :qtyMax ");
        return sb.toString();
    }

    private void setTableParams(Query q, String businessId, String branchId, String status, String search,
                                 String from, String to, String quantityMin, String quantityMax) {
        q.setParameter("businessId", businessId);
        if (branchId != null && !branchId.isBlank()) q.setParameter("branchId", branchId);
        if (status != null && !status.isBlank()) q.setParameter("status", status);
        if (search != null && !search.isBlank()) q.setParameter("search", "%" + search + "%");
        if (from != null && !from.isBlank()) q.setParameter("fromDate", from);
        if (to != null && !to.isBlank()) q.setParameter("toDate", to);
        if (quantityMin != null && !quantityMin.isBlank()) q.setParameter("qtyMin", new BigDecimal(quantityMin));
        if (quantityMax != null && !quantityMax.isBlank()) q.setParameter("qtyMax", new BigDecimal(quantityMax));
    }

    private String branchFilter(String branchId, String alias) {
        return (branchId != null && !branchId.isBlank()) ? " AND " + alias + ".branch_id = :branchId " : "";
    }

    private String dateRangeFilter(String from, String to, String dateCol) {
        StringBuilder sb = new StringBuilder();
        if (from != null && !from.isBlank()) sb.append(" AND DATE(").append(dateCol).append(") >= :fromDate ");
        if (to != null && !to.isBlank()) sb.append(" AND DATE(").append(dateCol).append(") <= :toDate ");
        return sb.toString();
    }

    private Long scalarLong(String sql, String businessId, String branchId) {
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("businessId", businessId);
        if (branchId != null && !branchId.isBlank()) q.setParameter("branchId", branchId);
        Object result = q.getSingleResult();
        return result != null ? ((Number) result).longValue() : 0L;
    }

    private BigDecimal scalarBd(String sql, String businessId, String branchId) {
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("businessId", businessId);
        if (branchId != null && !branchId.isBlank()) q.setParameter("branchId", branchId);
        Object result = q.getSingleResult();
        return result != null ? ((Number) result).doubleValue() == 0.0 ? BigDecimal.ZERO : new BigDecimal(result.toString()) : BigDecimal.ZERO;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }
}
