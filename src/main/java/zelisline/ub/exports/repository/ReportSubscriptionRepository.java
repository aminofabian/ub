package zelisline.ub.exports.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.exports.domain.ReportSubscription;

public interface ReportSubscriptionRepository extends JpaRepository<ReportSubscription, String> {

    List<ReportSubscription> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<ReportSubscription> findByBusinessIdAndActiveTrue(String businessId);

    Optional<ReportSubscription> findByIdAndBusinessId(String id, String businessId);

    Page<ReportSubscription> findByActiveTrue(Pageable pageable);

    /** Claim a period for one subscription; returns 1 only for the tick that wins the race. */
    @Modifying
    @Query("update ReportSubscription s set s.lastSentPeriod = :period, s.updatedAt = instant "
            + "where s.id = :id and (s.lastSentPeriod is null or s.lastSentPeriod <> :period)")
    int claimPeriod(@Param("id") String id, @Param("period") String period);
}
