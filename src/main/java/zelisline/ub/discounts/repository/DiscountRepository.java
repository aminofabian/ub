package zelisline.ub.discounts.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.discounts.domain.Discount;

public interface DiscountRepository extends JpaRepository<Discount, String> {

    List<Discount> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    Optional<Discount> findByIdAndBusinessId(String id, String businessId);

    @Query("""
            SELECT d FROM Discount d
            WHERE d.businessId = :businessId
              AND d.publishedAt IS NOT NULL
              AND d.paused = FALSE
              AND d.startAt <= :now
              AND (d.endAt IS NULL OR d.endAt > :now)
              AND (d.branchId IS NULL OR d.branchId = :branchId OR :branchId IS NULL)
            """)
    List<Discount> findResolvable(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("now") Instant now
    );
}
