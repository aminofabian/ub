package zelisline.ub.reporting.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.reporting.domain.MvSalesDaily;

public interface MvSalesDailyRepository extends JpaRepository<MvSalesDaily, MvSalesDaily.Key> {

    @Modifying
    @Query(value = "DELETE FROM mv_sales_daily WHERE business_id = :businessId", nativeQuery = true)
    int deleteForBusiness(@Param("businessId") String businessId);

    @Modifying
    @Query(value = """
            INSERT INTO mv_sales_daily (business_id, branch_id, business_day, item_id, qty, revenue, cost, profit, refreshed_at)
            SELECT s.business_id,
                   s.branch_id,
                   CAST(s.sold_at AS DATE)             AS business_day,
                   si.item_id,
                   COALESCE(SUM(si.quantity), 0)        AS qty,
                   COALESCE(SUM(si.line_total), 0)      AS revenue,
                   COALESCE(SUM(si.cost_total), 0)      AS cost,
                   COALESCE(SUM(si.profit), 0)          AS profit,
                   CURRENT_TIMESTAMP                    AS refreshed_at
              FROM sales s
              JOIN sale_items si ON si.sale_id = s.id
             WHERE s.business_id = :businessId
               AND s.status = 'completed'
             GROUP BY s.business_id, s.branch_id, CAST(s.sold_at AS DATE), si.item_id
            """, nativeQuery = true)
    int rebuildForBusiness(@Param("businessId") String businessId);

    interface DailyRollup {
        LocalDate getBusinessDay();
        String getBranchId();
        BigDecimal getQty();
        BigDecimal getRevenue();
        BigDecimal getCost();
        BigDecimal getProfit();
    }

    /**
     * Per-day per-branch totals for the sales register read facade. Branch filter is
     * optional; pass {@code null} to roll up across the tenant.
     */
    @Query(value = """
            SELECT m.business_day    AS business_day,
                   m.branch_id       AS branch_id,
                   COALESCE(SUM(m.qty), 0)     AS qty,
                   COALESCE(SUM(m.revenue), 0) AS revenue,
                   COALESCE(SUM(m.cost), 0)    AS cost,
                   COALESCE(SUM(m.profit), 0)  AS profit
              FROM mv_sales_daily m
             WHERE m.business_id = :businessId
               AND m.business_day >= :from
               AND m.business_day <= :to
               AND (:branchId IS NULL OR m.branch_id = :branchId)
             GROUP BY m.business_day, m.branch_id
             ORDER BY m.business_day, m.branch_id
            """, nativeQuery = true)
    List<DailyRollup> sumByDay(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("branchId") String branchId
    );

    /**
     * OLTP twin of {@link #sumByDay} for "today" — keeps the live window honest
     * regardless of MV refresh lag (PHASE_7_PLAN.md "today hybrid" rule).
     */
    @Query(value = """
            SELECT CAST(s.sold_at AS DATE) AS business_day,
                   s.branch_id              AS branch_id,
                   COALESCE(SUM(si.quantity), 0)   AS qty,
                   COALESCE(SUM(si.line_total), 0) AS revenue,
                   COALESCE(SUM(si.cost_total), 0) AS cost,
                   COALESCE(SUM(si.profit), 0)     AS profit
              FROM sales s
              JOIN sale_items si ON si.sale_id = s.id
             WHERE s.business_id = :businessId
               AND s.status = 'completed'
               AND CAST(s.sold_at AS DATE) = :targetDay
               AND (:branchId IS NULL OR s.branch_id = :branchId)
             GROUP BY CAST(s.sold_at AS DATE), s.branch_id
             ORDER BY s.branch_id
            """, nativeQuery = true)
    List<DailyRollup> sumOltpForDay(
            @Param("businessId") String businessId,
            @Param("targetDay") LocalDate targetDay,
            @Param("branchId") String branchId
    );

    interface ItemRevenue {
        String getItemId();

        BigDecimal getRevenue();
    }

    @Query(value = """
            SELECT m.item_id AS itemId,
                   COALESCE(SUM(m.revenue), 0) AS revenue
              FROM mv_sales_daily m
             WHERE m.business_id = :businessId
               AND m.business_day >= :from
               AND m.business_day <= :to
             GROUP BY m.item_id
             ORDER BY revenue DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<ItemRevenue> topItemsByRevenue(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("limit") int limit
    );
}
