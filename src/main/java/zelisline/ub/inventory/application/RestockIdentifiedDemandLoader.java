package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.inventory.application.RestockIdentifiedDemandMath.Demand;
import zelisline.ub.inventory.application.RestockIdentifiedDemandMath.PurchaseDay;
import zelisline.ub.sales.SalesConstants;

/**
 * Batch identified-demand read for a restock run. Fail-open: a SQL miss must
 * not block nightly velocity suggestions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestockIdentifiedDemandLoader {

    private static final int RHYTHM_LOOKBACK_DAYS = 180;
    private static final int IN_CHUNK = 400;

    private final JdbcTemplate jdbc;

    public Map<String, Demand> load(
            String businessId,
            String branchId,
            Collection<String> itemIds,
            LocalDate asOf
    ) {
        if (businessId == null || branchId == null || asOf == null
                || itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = itemIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            LocalDate rhythmFrom = asOf.minusDays(RHYTHM_LOOKBACK_DAYS);
            LocalDate captureFrom = asOf.minusDays(30);
            Map<String, List<PurchaseDay>> days = new HashMap<>();
            Map<String, BigDecimal[]> capture = new HashMap<>();
            for (int i = 0; i < ids.size(); i += IN_CHUNK) {
                List<String> chunk = ids.subList(i, Math.min(i + IN_CHUNK, ids.size()));
                mergeDays(days, queryDays(businessId, branchId, chunk, rhythmFrom, asOf));
                mergeCapture(capture, queryCapture(businessId, branchId, chunk, captureFrom, asOf));
            }
            Map<String, Demand> out = new HashMap<>();
            for (String itemId : ids) {
                BigDecimal[] qty = capture.getOrDefault(itemId, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                Demand demand = RestockIdentifiedDemandMath.compute(
                        days.getOrDefault(itemId, List.of()),
                        qty[0],
                        qty[1],
                        asOf);
                if (demand.regularCount() > 0 || (demand.explain() != null && !demand.explain().isBlank())) {
                    out.put(itemId, demand);
                }
            }
            return out;
        } catch (RuntimeException ex) {
            log.warn("Identified demand skipped for branch {}: {}", branchId, ex.toString());
            return Map.of();
        }
    }

    private Map<String, List<PurchaseDay>> queryDays(
            String businessId,
            String branchId,
            List<String> itemIds,
            LocalDate fromInclusive,
            LocalDate toExclusive
    ) {
        String sql = """
                SELECT sil.item_id,
                       s.customer_id,
                       CAST(s.sold_at AS DATE) AS sold_day,
                       COALESCE(SUM(sil.quantity), 0) AS qty
                  FROM sales s
                  JOIN sale_items sil ON sil.sale_id = s.id
                 WHERE s.business_id = ?
                   AND s.branch_id = ?
                   AND s.status = ?
                   AND s.voided_at IS NULL
                   AND s.customer_id IS NOT NULL
                   AND sil.item_id IN (""" + placeholders(itemIds.size()) + """
                   )
                   AND CAST(s.sold_at AS DATE) >= ?
                   AND CAST(s.sold_at AS DATE) < ?
                 GROUP BY sil.item_id, s.customer_id, CAST(s.sold_at AS DATE)
                """;
        List<Object> args = new ArrayList<>();
        args.add(businessId);
        args.add(branchId);
        args.add(SalesConstants.SALE_STATUS_COMPLETED);
        args.addAll(itemIds);
        args.add(Date.valueOf(fromInclusive));
        args.add(Date.valueOf(toExclusive));
        Map<String, List<PurchaseDay>> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            String itemId = rs.getString("item_id");
            String customerId = rs.getString("customer_id");
            Date sold = rs.getDate("sold_day");
            BigDecimal qty = rs.getBigDecimal("qty");
            if (itemId == null || customerId == null || sold == null) {
                return;
            }
            out.computeIfAbsent(itemId, k -> new ArrayList<>())
                    .add(new PurchaseDay(customerId, sold.toLocalDate(), qty));
        }, args.toArray());
        return out;
    }

    private Map<String, BigDecimal[]> queryCapture(
            String businessId,
            String branchId,
            List<String> itemIds,
            LocalDate fromInclusive,
            LocalDate toExclusive
    ) {
        String sql = """
                SELECT sil.item_id,
                       COALESCE(SUM(CASE WHEN s.customer_id IS NOT NULL THEN sil.quantity ELSE 0 END), 0) AS linked_qty,
                       COALESCE(SUM(sil.quantity), 0) AS all_qty
                  FROM sales s
                  JOIN sale_items sil ON sil.sale_id = s.id
                 WHERE s.business_id = ?
                   AND s.branch_id = ?
                   AND s.status = ?
                   AND s.voided_at IS NULL
                   AND sil.item_id IN (""" + placeholders(itemIds.size()) + """
                   )
                   AND CAST(s.sold_at AS DATE) >= ?
                   AND CAST(s.sold_at AS DATE) < ?
                 GROUP BY sil.item_id
                """;
        List<Object> args = new ArrayList<>();
        args.add(businessId);
        args.add(branchId);
        args.add(SalesConstants.SALE_STATUS_COMPLETED);
        args.addAll(itemIds);
        args.add(Date.valueOf(fromInclusive));
        args.add(Date.valueOf(toExclusive));
        Map<String, BigDecimal[]> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            String itemId = rs.getString("item_id");
            if (itemId == null) {
                return;
            }
            out.put(itemId, new BigDecimal[] {
                    nz(rs.getBigDecimal("linked_qty")),
                    nz(rs.getBigDecimal("all_qty"))
            });
        }, args.toArray());
        return out;
    }

    private static void mergeDays(
            Map<String, List<PurchaseDay>> into,
            Map<String, List<PurchaseDay>> add
    ) {
        for (Map.Entry<String, List<PurchaseDay>> e : add.entrySet()) {
            into.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }
    }

    private static void mergeCapture(
            Map<String, BigDecimal[]> into,
            Map<String, BigDecimal[]> add
    ) {
        into.putAll(add);
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
