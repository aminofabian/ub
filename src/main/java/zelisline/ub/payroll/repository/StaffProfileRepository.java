package zelisline.ub.payroll.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.payroll.domain.StaffProfile;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, String> {

    Optional<StaffProfile> findByBusinessIdAndUserId(String businessId, String userId);

    Optional<StaffProfile> findByIdAndBusinessId(String id, String businessId);

    List<StaffProfile> findByBusinessIdOrderByDisplayNameAsc(String businessId);

    List<StaffProfile> findByBusinessIdAndUserIdIsNull(String businessId);

    /** Row lock, so two payouts for one staff member cannot both read the same balance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StaffProfile p where p.id = :id and p.businessId = :businessId")
    Optional<StaffProfile> findByIdAndBusinessIdForUpdate(
            @Param("id") String id, @Param("businessId") String businessId);

    boolean existsByBusinessIdAndLoginlessNameKeyAndIdNot(String businessId, String loginlessNameKey, String id);

    boolean existsByBusinessIdAndLoginlessNameKey(String businessId, String loginlessNameKey);

    /** Ids only, so callers that just need "is this a current staff member" load no HR data. */
    @Query("select p.id from StaffProfile p where p.businessId = :businessId and p.employmentStatus <> :excludedStatus")
    List<String> findIdsByBusinessIdAndEmploymentStatusNot(
            @Param("businessId") String businessId,
            @Param("excludedStatus") String excludedStatus);

    /** Ids of current staff who perform services. */
    @Query("""
            select p.id from StaffProfile p
            where p.businessId = :businessId and p.bookable = true and p.employmentStatus <> :excludedStatus
            """)
    List<String> findBookableIdsByBusinessId(
            @Param("businessId") String businessId,
            @Param("excludedStatus") String excludedStatus);
}
