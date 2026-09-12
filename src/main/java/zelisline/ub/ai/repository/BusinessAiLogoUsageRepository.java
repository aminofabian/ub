package zelisline.ub.ai.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.ai.domain.BusinessAiLogoUsage;

public interface BusinessAiLogoUsageRepository extends JpaRepository<BusinessAiLogoUsage, String> {

    Optional<BusinessAiLogoUsage> findByBusinessId(String businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from BusinessAiLogoUsage u where u.businessId = :businessId")
    Optional<BusinessAiLogoUsage> findForUpdate(@Param("businessId") String businessId);
}
