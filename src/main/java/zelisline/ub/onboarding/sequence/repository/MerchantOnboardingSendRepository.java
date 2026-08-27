package zelisline.ub.onboarding.sequence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.onboarding.sequence.domain.MerchantOnboardingSend;

public interface MerchantOnboardingSendRepository extends JpaRepository<MerchantOnboardingSend, String> {

    long countByBusinessIdAndChannel(String businessId, String channel);

    long countByBusinessIdAndChannelAndStatus(String businessId, String channel, String status);

    boolean existsByBusinessIdAndStepKeyAndChannel(String businessId, String stepKey, String channel);

    Optional<MerchantOnboardingSend> findByBusinessIdAndStepKeyAndChannel(
            String businessId, String stepKey, String channel);

    boolean existsByBusinessIdAndStepKeyAndStatus(String businessId, String stepKey, String status);

    @Query("""
            select count(s) > 0 from MerchantOnboardingSend s
            where s.businessId = :businessId
              and s.channel = :channel
              and s.status = :status
              and s.sentAt is not null
              and s.sentAt >= :since
            """)
    boolean existsSentSince(
            @Param("businessId") String businessId,
            @Param("channel") String channel,
            @Param("status") String status,
            @Param("since") Instant since);
}
