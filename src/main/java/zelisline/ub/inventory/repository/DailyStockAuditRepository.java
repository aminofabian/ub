package zelisline.ub.inventory.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.DailyStockAudit;

public interface DailyStockAuditRepository extends JpaRepository<DailyStockAudit, String> {

    @Query("""
            select a from DailyStockAudit a
             left join fetch a.items
             where a.businessId = :businessId
               and a.branchId = :branchId
               and a.auditDate = :auditDate
            """)
    Optional<DailyStockAudit> findByBusinessBranchAndDateFetchItems(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("auditDate") LocalDate auditDate
    );

    boolean existsByBusinessIdAndBranchIdAndAuditDate(
            String businessId,
            String branchId,
            LocalDate auditDate
    );

    @Query("""
            select a from DailyStockAudit a
             left join fetch a.items
             where a.id = :id and a.businessId = :businessId
            """)
    Optional<DailyStockAudit> findByIdAndBusinessIdFetchItems(
            @Param("id") String id,
            @Param("businessId") String businessId
    );

    @Query("""
            select a from DailyStockAudit a
             left join fetch a.items
             where a.businessId = :businessId
               and (:branchId is null or a.branchId = :branchId)
               and (:from is null or a.auditDate >= :from)
               and (:to is null or a.auditDate <= :to)
             order by a.auditDate desc
            """)
    List<DailyStockAudit> findFilteredFetchItems(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
