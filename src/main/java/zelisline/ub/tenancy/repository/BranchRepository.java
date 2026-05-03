package zelisline.ub.tenancy.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.tenancy.domain.Branch;

public interface BranchRepository extends JpaRepository<Branch, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Branch b
           set b.deletedAt = :ts,
               b.updatedAt = :ts
         where b.businessId = :businessId
           and b.deletedAt is null
        """)
    int softDeleteAllByBusinessId(@Param("businessId") String businessId, @Param("ts") Instant ts);

    Page<Branch> findByBusinessIdAndDeletedAtIsNull(String businessId, Pageable pageable);

    Optional<Branch> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    List<Branch> findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(String businessId);

    boolean existsByBusinessIdAndNameAndDeletedAtIsNull(String businessId, String name);
}
