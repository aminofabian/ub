package zelisline.ub.platform.logs;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.criteria.Predicate;

public interface PlatformRequestLogRepository
        extends JpaRepository<PlatformRequestLog, String>,
        JpaSpecificationExecutor<PlatformRequestLog> {

    @Query(value = """
            SELECT category          AS category,
                   COUNT(*)          AS total,
                   SUM(CASE WHEN success = TRUE THEN 1 ELSE 0 END) AS ok,
                   COALESCE(AVG(duration_ms), 0)                AS avgMs,
                   MAX(logged_at)                               AS lastAt
            FROM platform_request_log
            WHERE (:since IS NULL OR logged_at >= :since)
            GROUP BY category
            """, nativeQuery = true)
    List<CategorySummaryRow> summarySince(@Param("since") Instant since);

    /** Bulk purge used by {@link RequestLogRetention} — returns rows removed. */
    long deleteByLoggedAtBefore(Instant cutoff);

    /**
     * Dynamic filter for the live feed — only the predicates the caller
     * supplied are added, so no nullable-parameter SQL is generated.
     */
    static Specification<PlatformRequestLog> matches(
            RequestLogCategory category, Boolean success, Instant since, String ip) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (success != null) {
                predicates.add(cb.equal(root.get("success"), success));
            }
            if (since != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("loggedAt"), since));
            }
            if (ip != null && !ip.isBlank()) {
                String pattern = "%" + ip.trim().toLowerCase() + "%";
                predicates.add(cb.like(cb.lower(root.get("ip")), pattern));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Native projection — one row per {@link RequestLogCategory} present in the window. */
    interface CategorySummaryRow {
        String getCategory();

        long getTotal();

        long getOk();

        double getAvgMs();

        /** DATETIME column — Hibernate 7 reads it as {@link LocalDateTime} (UTC session). */
        LocalDateTime getLastAt();
    }
}
