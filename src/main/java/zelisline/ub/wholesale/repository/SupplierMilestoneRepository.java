package zelisline.ub.wholesale.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.wholesale.domain.SupplierMilestone;

public interface SupplierMilestoneRepository extends JpaRepository<SupplierMilestone, String> {

    boolean existsByBusinessIdAndMilestoneKey(String businessId, String milestoneKey);

    List<SupplierMilestone> findByBusinessIdOrderByAchievedAtAsc(String businessId);
}
