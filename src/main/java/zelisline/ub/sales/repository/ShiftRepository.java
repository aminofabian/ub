package zelisline.ub.sales.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.tillDeviceKey = :tillDeviceKey
               and s.status = :status
            """)
    Optional<Shift> findByBusinessIdAndBranchIdAndTillDeviceKeyAndStatus(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("tillDeviceKey") String tillDeviceKey,
            @Param("status") String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.tillDeviceKey = :tillDeviceKey
               and s.status = :status
            """)
    Optional<Shift> findByBusinessIdAndBranchIdAndTillDeviceKeyAndStatusForUpdate(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("tillDeviceKey") String tillDeviceKey,
            @Param("status") String status
    );

    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.tillDeviceKey is null
               and s.status = :status
            """)
    Optional<Shift> findByBusinessIdAndBranchIdAndTillDeviceKeyIsNullAndStatus(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.tillDeviceKey is null
               and s.status = :status
            """)
    Optional<Shift> findByBusinessIdAndBranchIdAndTillDeviceKeyIsNullAndStatusForUpdate(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status
    );

    /** Recently closed shifts for a till (or shared branch when tillDeviceKey is null). */
    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.status = :status
               and (
                    (:tillDeviceKey is null and s.tillDeviceKey is null)
                    or s.tillDeviceKey = :tillDeviceKey
               )
             order by s.closedAt desc nulls last, s.openedAt desc
            """)
    List<Shift> findClosedByBusinessIdAndBranchIdAndTillDeviceKey(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("tillDeviceKey") String tillDeviceKey,
            @Param("status") String status,
            Pageable pageable
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

    /** List all shifts for a business, latest first. */
    @Query("""
            select s from Shift s
             where s.businessId = :businessId
             order by s.openedAt desc
            """)
    Page<Shift> findByBusinessIdOrderByOpenedAtDesc(
            @Param("businessId") String businessId,
            Pageable pageable
    );

    /** List shifts for a business, filtered by branch. */
    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and (:branchId is null or s.branchId = :branchId)
               and (:status is null or s.status = :status)
             order by s.openedAt desc
            """)
    Page<Shift> findByBusinessIdFiltered(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            Pageable pageable
    );

    /** List shifts opened by a specific cashier. */
    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.openedBy = :openedBy
             order by s.openedAt desc
            """)
    Page<Shift> findByBusinessIdAndOpenedByOrderByOpenedAtDesc(
            @Param("businessId") String businessId,
            @Param("openedBy") String openedBy,
            Pageable pageable
    );

    /** List shifts opened by a cashier at a specific branch (branch-scoped role lists). */
    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.openedBy = :openedBy
               and s.branchId = :branchId
             order by s.openedAt desc
            """)
    Page<Shift> findByBusinessIdAndOpenedByAndBranchIdOrderByOpenedAtDesc(
            @Param("businessId") String businessId,
            @Param("openedBy") String openedBy,
            @Param("branchId") String branchId,
            Pageable pageable
    );

    /** Count open shifts for a specific branch. */
    long countByBusinessIdAndBranchIdAndStatus(
            String businessId, String branchId, String status);

    /** Recently closed shifts at a branch (by closedAt, then openedAt). Use page size 1 for latest. */
    @Query("""
            select s from Shift s
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.status = :status
             order by s.closedAt desc nulls last, s.openedAt desc
            """)
    List<Shift> findClosedByBusinessIdAndBranchId(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            Pageable pageable
    );
}
