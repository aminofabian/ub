package zelisline.ub.identity.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import zelisline.ub.identity.domain.UserOAuthIdentity;

public interface UserOAuthIdentityRepository extends JpaRepository<UserOAuthIdentity, String> {

    Optional<UserOAuthIdentity> findByProviderAndProviderSubjectAndBusinessId(
            String provider, String providerSubject, String businessId);

    Optional<UserOAuthIdentity> findByUserIdAndProvider(String userId, String provider);

    List<UserOAuthIdentity> findByUserId(String userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from UserOAuthIdentity u where u.userId = :userId")
    void deleteByUserId(@Param("userId") String userId);
}
