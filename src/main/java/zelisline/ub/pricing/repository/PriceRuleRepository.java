package zelisline.ub.pricing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pricing.domain.PriceRule;

public interface PriceRuleRepository extends JpaRepository<PriceRule, String> {

    Optional<PriceRule> findByIdAndBusinessId(String id, String businessId);

    List<PriceRule> findByBusinessIdAndActiveIsTrueOrderByNameAsc(String businessId);

    List<PriceRule> findByBusinessIdOrderByNameAsc(String businessId);

    boolean existsByBusinessIdAndNameAndIdNot(String businessId, String name, String excludeId);

    boolean existsByBusinessIdAndName(String businessId, String name);
}
