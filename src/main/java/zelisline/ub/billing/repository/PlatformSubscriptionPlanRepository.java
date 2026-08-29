package zelisline.ub.billing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.billing.domain.PlatformSubscriptionPlan;

public interface PlatformSubscriptionPlanRepository extends JpaRepository<PlatformSubscriptionPlan, String> {

    Optional<PlatformSubscriptionPlan> findByTierCodeAndActiveTrue(String tierCode);

    List<PlatformSubscriptionPlan> findAllByOrderBySortOrderAsc();

    List<PlatformSubscriptionPlan> findAllByActiveTrueOrderBySortOrderAsc();
}
