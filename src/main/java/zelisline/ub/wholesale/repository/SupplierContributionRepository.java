package zelisline.ub.wholesale.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.wholesale.domain.SupplierContribution;

public interface SupplierContributionRepository extends JpaRepository<SupplierContribution, String> {

    boolean existsByBusinessIdAndUserIdAndRuleIdAndSourceTypeAndSourceId(
            String businessId, String userId, String ruleId, String sourceType, String sourceId);

    long countByBusinessIdAndUserIdAndRuleId(String businessId, String userId, String ruleId);

    /** Whoever did it: used for a rule that counts a source once for the whole shop. */
    boolean existsByBusinessIdAndRuleIdAndSourceTypeAndSourceId(
            String businessId, String ruleId, String sourceType, String sourceId);
}
