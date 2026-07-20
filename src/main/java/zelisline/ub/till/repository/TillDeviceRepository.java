package zelisline.ub.till.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.till.domain.TillDevice;

public interface TillDeviceRepository extends JpaRepository<TillDevice, String> {

    Optional<TillDevice> findByBusinessIdAndBranchIdAndDeviceKey(
            String businessId,
            String branchId,
            String deviceKey);

    List<TillDevice> findByBusinessIdAndBranchIdAndRevokedAtIsNullOrderByRegisteredAtDesc(
            String businessId,
            String branchId);

    List<TillDevice> findByBusinessIdAndBranchIdOrderByRegisteredAtDesc(
            String businessId,
            String branchId);

    Optional<TillDevice> findByIdAndBusinessId(String id, String businessId);

    boolean existsByBusinessIdAndBranchIdAndRevokedAtIsNull(String businessId, String branchId);

    boolean existsByBusinessIdAndBranchIdAndDeviceKeyAndRevokedAtIsNull(
            String businessId,
            String branchId,
            String deviceKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update TillDevice t
           set t.revokedAt = :revokedAt,
               t.updatedAt = :revokedAt
         where t.id = :id
           and t.businessId = :businessId
           and t.revokedAt is null
        """)
    int revoke(
            @Param("id") String id,
            @Param("businessId") String businessId,
            @Param("revokedAt") Instant revokedAt);
}
