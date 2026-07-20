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
     * Per-day per-branch totals for the sales register read facade. Branch / item-type
     * filters are optional; pass {@code null} to roll up across the tenant / all departments.
     */
    @Query(value = """
            SELECT m.business_day    AS business_day,
                   m.branch_id       AS branch_id,
                   COALESCE(SUM(m.qty), 0)     AS qty,
                   COALESCE(SUM(m.revenue), 0) AS revenue,
                   COALESCE(SUM(m.cost), 0)    AS cost,
                   COALESCE(SUM(m.profit), 0)  AS profit
              FROM mv_sales_daily m
              JOIN items i ON i.id = m.item_id AND i.business_id = m.business_id AND i.deleted_at IS NULL
             WHERE m.business_id = :businessId
               AND m.business_day >= :from
               AND m.business_day <= :to
               AND (:branchId IS NULL OR m.branch_id = :branchId)
               AND (:itemTypeId IS NULL OR i.item_type_id = :itemTypeId)
             GROUP BY m.business_day, m.branch_id
             ORDER BY m.business_day, m.branch_id
            """, nativeQuery = true)
    List<DailyRollup> sumByDay(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId
    );

    @Query(value = """
            SELECT COALESCE(SUM(m.revenue), 0)
              FROM mv_sales_daily m
             WHERE m.business_id = :businessId
               AND m.business_day = :businessDay
            """, nativeQuery = true)
    BigDecimal sumRevenueForBusinessDay(
            @Param("businessId") String businessId,
            @Param("businessDay") LocalDate businessDay);

    /**
     * OLTP twin of {@link #sumByDay} for a single calendar day — keeps the live
     * window honest regardless of MV refresh lag (PHASE_7_PLAN.md "today hybrid").
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
              JOIN items i ON i.id = si.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
             WHERE s.business_id = :businessId
               AND s.status = 'completed'
               AND CAST(s.sold_at AS DATE) = :targetDay
               AND (:branchId IS NULL OR s.branch_id = :branchId)
               AND (:itemTypeId IS NULL OR i.item_type_id = :itemTypeId)
             GROUP BY CAST(s.sold_at AS DATE), s.branch_id
             ORDER BY s.branch_id
            """, nativeQuery = true)
    List<DailyRollup> sumOltpForDay(
            @Param("businessId") String businessId,
            @Param("targetDay") LocalDate targetDay,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId
    );

    /**
     * OLTP twin of {@link #sumByDay} for a closed date range. Used to gap-fill past
     * days when {@code mv_sales_daily} has not been refreshed yet for the tenant.
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
              JOIN items i ON i.id = si.item_id AND i.business_id = s.business_id AND i.deleted_at IS NULL
             WHERE s.business_id = :businessId
               AND s.status = 'completed'
               AND CAST(s.sold_at AS DATE) >= :from
               AND CAST(s.sold_at AS DATE) <= :to
               AND (:branchId IS NULL OR s.branch_id = :branchId)
               AND (:itemTypeId IS NULL OR i.item_type_id = :itemTypeId)
             GROUP BY CAST(s.sold_at AS DATE), s.branch_id
             ORDER BY CAST(s.sold_at AS DATE), s.branch_id
            """, nativeQuery = true)
    List<DailyRollup> sumOltpByDay(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId
    );

    interface ItemRevenue {
        String getItemId();

        BigDecimal getRevenue();
    }

    @Query(value = """
            SELECT m.item_id AS itemId,
                   COALESCE(SUM(m.revenue), 0) AS revenue
              FROM mv_sales_daily m
              JOIN items i ON i.id = m.item_id AND i.business_id = m.business_id AND i.deleted_at IS NULL
             WHERE m.business_id = :businessId
               AND m.business_day >= :from
               AND m.business_day <= :to
               AND (:branchId IS NULL OR m.branch_id = :branchId)
               AND (:itemTypeId IS NULL OR i.item_type_id = :itemTypeId)
             GROUP BY m.item_id
             ORDER BY revenue DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<ItemRevenue> topItemsByRevenue(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId,
            @Param("limit") int limit
    );

    interface PeakHourRow {
        String getPeakHour();

        BigDecimal getRevenue();
    }

    @Query(value = """
            SELECT DATE_FORMAT(CONVERT_TZ(s.sold_at, '+00:00', '+03:00'), '%H:00') AS peakHour,
                   COALESCE(SUM(s.grand_total), 0) AS revenue
              FROM sales s
             WHERE s.business_id = :businessId
               AND s.status = 'completed'
               AND DATE(CONVERT_TZ(s.sold_at, '+00:00', '+03:00')) = :businessDay
             GROUP BY HOUR(CONVERT_TZ(s.sold_at, '+00:00', '+03:00'))
             ORDER BY revenue DESC
             LIMIT 1
            """, nativeQuery = true)
    List<PeakHourRow> findPeakSalesHourForDay(
            @Param("businessId") String businessId,
            @Param("businessDay") LocalDate businessDay);
}
