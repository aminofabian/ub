package zelisline.ub.notifications.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.notifications.domain.DeviceToken;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, String> {

    List<DeviceToken> findByBusinessIdAndUserIdAndRevokedAtIsNull(String businessId, String userId);

    Optional<DeviceToken> findByEndpointAndRevokedAtIsNull(String endpoint);

    Optional<DeviceToken> findByBusinessIdAndUserIdAndPlatformAndTokenAndRevokedAtIsNull(
            String businessId,
            String userId,
            String platform,
            String token);

    Optional<DeviceToken> findByIdAndBusinessIdAndUserIdAndRevokedAtIsNull(
            String id,
            String businessId,
            String userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update DeviceToken t
           set t.revokedAt = :revokedAt
         where t.id = :id
           and t.businessId = :businessId
           and t.userId = :userId
           and t.revokedAt is null
        """)
    int revoke(
            @Param("id") String id,
            @Param("businessId") String businessId,
            @Param("userId") String userId,
            @Param("revokedAt") Instant revokedAt);

    List<DeviceToken> findByBusinessIdAndUserIdInAndRevokedAtIsNull(
            String businessId,
            List<String> userIds);
}
