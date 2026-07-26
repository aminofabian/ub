package zelisline.ub.marketplace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.marketplace.domain.SupplierUserSession;

public interface SupplierUserSessionRepository extends JpaRepository<SupplierUserSession, String> {

    Optional<SupplierUserSession> findByAccessTokenJtiAndRevokedAtIsNull(String accessTokenJti);

    Optional<SupplierUserSession> findByAccessTokenJti(String accessTokenJti);

    List<SupplierUserSession> findBySupplierUserIdOrderByIssuedAtDesc(String supplierUserId);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE SupplierUserSession s
            SET s.revokedAt = :now
            WHERE s.supplierUserId = :supplierUserId
              AND s.revokedAt IS NULL
            """)
    int revokeAllActiveForUser(
            @Param("supplierUserId") String supplierUserId,
            @Param("now") java.time.Instant now
    );

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE SupplierUserSession s
            SET s.lastSeenAt = :now
            WHERE s.accessTokenJti = :jti
              AND s.revokedAt IS NULL
            """)
    int touchLastSeen(@Param("jti") String jti, @Param("now") java.time.Instant now);
}
