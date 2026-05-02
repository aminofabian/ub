package zelisline.ub.identity.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.identity.domain.EmailVerificationToken;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {

    Optional<EmailVerificationToken> findByTokenHashAndUsedAtIsNull(String tokenHash);

    @Modifying
    @Query("""
           delete from EmailVerificationToken t
            where t.userId = :userId and t.usedAt is null
           """)
    void deleteUnusedByUserId(@Param("userId") String userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EmailVerificationToken t where t.userId in :userIds")
    void deleteAllByUserIdIn(@Param("userIds") List<String> userIds);
}
