package zelisline.ub.sales.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.RecurringDrawoutItem;

public interface RecurringDrawoutItemRepository extends JpaRepository<RecurringDrawoutItem, String> {

    List<RecurringDrawoutItem> findByBusinessIdAndIsActiveOrderByNameAsc(String businessId, boolean isActive);

    List<RecurringDrawoutItem> findByBusinessIdOrderByNameAsc(String businessId);

    Optional<RecurringDrawoutItem> findByIdAndBusinessId(String id, String businessId);
}
