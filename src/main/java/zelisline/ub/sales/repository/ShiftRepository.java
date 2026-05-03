package zelisline.ub.sales.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.sales.domain.Shift;

public interface ShiftRepository extends JpaRepository<Shift, String> {

    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.status = :status
            """)
    Optional<Shift> findByBusinessIdAndBranchIdAndStatus(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.status = :status
            """)
    Optional<Shift> findByBusinessIdAndBranchIdAndStatusForUpdate(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Shift s
             where s.id = :id and s.businessId = :businessId
            """)
    Optional<Shift> findByIdAndBusinessIdForUpdate(
            @Param("id") String id,
            @Param("businessId") String businessId
    );

    Optional<Shift> findByIdAndBusinessId(String id, String businessId);
}
