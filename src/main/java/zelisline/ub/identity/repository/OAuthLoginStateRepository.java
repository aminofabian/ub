package zelisline.ub.identity.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.identity.domain.OAuthLoginState;

public interface OAuthLoginStateRepository extends JpaRepository<OAuthLoginState, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OAuthLoginState s where s.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
