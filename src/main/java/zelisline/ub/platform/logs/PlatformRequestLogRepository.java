package zelisline.ub.platform.logs;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformRequestLogRepository extends JpaRepository<PlatformRequestLog, String> {

    @Query("""
            SELECT p FROM PlatformRequestLog p
            WHERE (:category IS NULL OR p.category = :category)
              AND (:success IS NULL OR p.success = :success)
              AND (:since IS NULL OR p.loggedAt >= :since)
            ORDER BY p.loggedAt DESC
            """)
    List<PlatformRequestLog> search(
            @Param("category") RequestLogCategory category,
            @Param("success") Boolean success,
            @Param("since") Instant since,
            Pageable pageable);

    @Query(value = """
            SELECT category          AS category,
                   COUNT(*)          AS total,
                   SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS ok,
                   COALESCE(AVG(duration_ms), 0)                AS avgMs,
                   MAX(logged_at)                               AS lastAt
            FROM platform_request_log
            WHERE (:since IS NULL OR logged_at >= :since)
            GROUP BY category
            """, nativeQuery = true)
    List<CategorySummaryRow> summarySince(@Param("since") Instant since);

    /** Bulk purge used by {@link RequestLogRetention} — returns rows removed. */
    long deleteByLoggedAtBefore(Instant cutoff);

    /** Native projection — one row per {@link RequestLogCategory} present in the window. */
    interface CategorySummaryRow {
        String getCategory();

        long getTotal();

        long getOk();

        double getAvgMs();

        Timestamp getLastAt();
    }
}
