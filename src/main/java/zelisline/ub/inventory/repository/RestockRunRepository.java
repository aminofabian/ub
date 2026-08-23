package zelisline.ub.inventory.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.RestockRun;

public interface RestockRunRepository extends JpaRepository<RestockRun, String> {

    Optional<RestockRun> findByIdAndBusinessId(String id, String businessId);

    Optional<RestockRun> findByBranchIdAndRunDate(String branchId, LocalDate runDate);

    Optional<RestockRun> findFirstByBranchIdOrderByRunDateDescIdDesc(String branchId);

    List<RestockRun> findByBranchIdAndRunDateBeforeAndStatusIn(
            String branchId, LocalDate runDate, List<String> statuses);

    @Query("""
            select r from RestockRun r
             where r.businessId = :businessId
               and (:branchId is null or r.branchId = :branchId)
               and (:from is null or r.runDate >= :from)
               and (:to is null or r.runDate <= :to)
             order by r.runDate desc, r.createdAt desc
            """)
    List<RestockRun> findForList(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
