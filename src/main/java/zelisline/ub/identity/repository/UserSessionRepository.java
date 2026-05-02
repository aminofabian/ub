package zelisline.ub.identity.repository;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.identity.domain.UserSession;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    Optional<UserSession> findByAccessTokenJtiAndRevokedAtIsNull(String accessTokenJti);

    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserSession s WHERE s.refreshTokenHash = :hash")
    Optional<UserSession> findByRefreshTokenHashForUpdate(@Param("hash") String hash);

    @Modifying
    @Query("""
        update UserSession s
           set s.revokedAt = :revokedAt
         where s.userId = :userId
           and s.revokedAt is null
        """)
    int revokeAllActiveForUser(@Param("userId") String userId, @Param("revokedAt") Instant revokedAt);
}
