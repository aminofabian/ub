package zelisline.ub.platform.overview;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.sales.domain.Sale;

/**
 * Cross-tenant aggregates for the super-admin overview. Uses native SQL so
 * MySQL can push date grouping and top-N without loading entity graphs.
 */
public interface PlatformOverviewRepository extends JpaRepository<Sale, String> {

    @Query(
            value = """
                    SELECT COUNT(*),
                           COALESCE(SUM(s.grand_total), 0)
                      FROM sales s
                     WHERE s.status = 'completed'
                       AND s.voided_at IS NULL
                       AND s.sold_at >= :since
                       AND s.sold_at < :until
                    """,
            nativeQuery = true
    )
    List<Object[]> salesAggregateBetween(@Param("since") Instant since, @Param("until") Instant until);

    @Query(
            value = """
                    SELECT COUNT(*),
                           COALESCE(SUM(s.grand_total), 0)
                      FROM sales s
                     WHERE s.status = 'completed'
                       AND s.voided_at IS NULL
                    """,
            nativeQuery = true
    )
    List<Object[]> salesAggregateAllTime();

    @Query(
            value = """
                    SELECT COALESCE(SUM(si.quantity), 0)
                      FROM sale_items si
                      INNER JOIN sales s ON s.id = si.sale_id
                     WHERE s.status = 'completed'
                       AND s.voided_at IS NULL
                       AND si.line_kind = 'ITEM'
                       AND si.item_id IS NOT NULL
                       AND s.sold_at >= :since
                       AND s.sold_at < :until
                    """,
            nativeQuery = true
    )
    BigDecimal unitsSoldBetween(@Param("since") Instant since, @Param("until") Instant until);

    @Query(
            value = """
                    SELECT COALESCE(SUM(si.quantity), 0)
                      FROM sale_items si
                      INNER JOIN sales s ON s.id = si.sale_id
                     WHERE s.status = 'completed'
                       AND s.voided_at IS NULL
                       AND si.line_kind = 'ITEM'
                       AND si.item_id IS NOT NULL
                    """,
            nativeQuery = true
    )
    BigDecimal unitsSoldAllTime();

    @Query(
            value = """
                    SELECT si.item_id,
                           i.name,
                           s.business_id,
                           b.name,
                           COALESCE(SUM(si.quantity), 0),
                           COALESCE(SUM(si.line_total), 0),
                           COUNT(DISTINCT s.id)
                      FROM sale_items si
                      INNER JOIN sales s ON s.id = si.sale_id
                      INNER JOIN items i ON i.id = si.item_id
                      INNER JOIN businesses b ON b.id = s.business_id
                     WHERE s.status = 'completed'
                       AND s.voided_at IS NULL
                       AND si.line_kind = 'ITEM'
                       AND si.item_id IS NOT NULL
                       AND i.deleted_at IS NULL
                       AND b.deleted_at IS NULL
                       AND s.sold_at >= :since
                     GROUP BY si.item_id, i.name, s.business_id, b.name
                     ORDER BY COALESCE(SUM(si.quantity), 0) DESC
                     LIMIT 10
                    """,
            nativeQuery = true
    )
    List<Object[]> topSellersSince(@Param("since") Instant since);

    @Query(
            value = """
                    SELECT t.business_id,
                           b.name,
                           b.slug,
                           t.sale_count,
                           t.revenue,
                           COALESCE(u.units, 0)
                      FROM (
                            SELECT s.business_id AS business_id,
                                   COUNT(*) AS sale_count,
                                   COALESCE(SUM(s.grand_total), 0) AS revenue
                              FROM sales s
                             WHERE s.status = 'completed'
                               AND s.voided_at IS NULL
                               AND s.sold_at >= :since
                             GROUP BY s.business_id
                           ) t
                      INNER JOIN businesses b ON b.id = t.business_id AND b.deleted_at IS NULL
                      LEFT JOIN (
                            SELECT s.business_id AS business_id,
                                   COALESCE(SUM(si.quantity), 0) AS units
                              FROM sale_items si
                              INNER JOIN sales s ON s.id = si.sale_id
                             WHERE s.status = 'completed'
                               AND s.voided_at IS NULL
                               AND si.line_kind = 'ITEM'
                               AND si.item_id IS NOT NULL
                               AND s.sold_at >= :since
                             GROUP BY s.business_id
                           ) u ON u.business_id = t.business_id
                     ORDER BY t.revenue DESC
                     LIMIT 8
                    """,
            nativeQuery = true
    )
    List<Object[]> hotTenantsSince(@Param("since") Instant since);

    @Query(
            value = """
                    SELECT d.day,
                           d.sale_count,
                           d.revenue,
                           COALESCE(u.units, 0)
                      FROM (
                            SELECT DATE(s.sold_at) AS day,
                                   COUNT(*) AS sale_count,
                                   COALESCE(SUM(s.grand_total), 0) AS revenue
                              FROM sales s
                             WHERE s.status = 'completed'
                               AND s.voided_at IS NULL
                               AND s.sold_at >= :since
                             GROUP BY DATE(s.sold_at)
                           ) d
                      LEFT JOIN (
                            SELECT DATE(s.sold_at) AS day,
                                   COALESCE(SUM(si.quantity), 0) AS units
                              FROM sale_items si
                              INNER JOIN sales s ON s.id = si.sale_id
                             WHERE s.status = 'completed'
                               AND s.voided_at IS NULL
                               AND si.line_kind = 'ITEM'
                               AND si.item_id IS NOT NULL
                               AND s.sold_at >= :since
                             GROUP BY DATE(s.sold_at)
                           ) u ON u.day = d.day
                     ORDER BY d.day ASC
                    """,
            nativeQuery = true
    )
    List<Object[]> dailySalesSince(@Param("since") Instant since);

    @Query(
            value = """
                    SELECT COUNT(*),
                           COALESCE(SUM(w.grand_total), 0)
                      FROM web_orders w
                     WHERE w.paid_at IS NOT NULL
                       AND w.paid_at >= :since
                       AND w.paid_at < :until
                    """,
            nativeQuery = true
    )
    List<Object[]> storefrontPaidBetween(@Param("since") Instant since, @Param("until") Instant until);

    @Query(
            value = """
                    SELECT COUNT(*),
                           COALESCE(SUM(w.grand_total), 0)
                      FROM web_orders w
                     WHERE w.paid_at IS NOT NULL
                    """,
            nativeQuery = true
    )
    List<Object[]> storefrontPaidAllTime();

    @Query(
            value = """
                    SELECT COALESCE(SUM(l.quantity), 0)
                      FROM web_order_lines l
                      INNER JOIN web_orders w ON w.id = l.order_id
                     WHERE w.paid_at IS NOT NULL
                       AND w.paid_at >= :since
                       AND w.paid_at < :until
                    """,
            nativeQuery = true
    )
    BigDecimal storefrontUnitsBetween(@Param("since") Instant since, @Param("until") Instant until);
}
