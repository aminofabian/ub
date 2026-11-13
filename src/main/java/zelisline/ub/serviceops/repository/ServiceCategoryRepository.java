package zelisline.ub.serviceops.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.serviceops.domain.ServiceCategory;

public interface ServiceCategoryRepository extends JpaRepository<ServiceCategory, String> {

    List<ServiceCategory> findByBusinessIdOrderBySortOrderAscNameAsc(String businessId);

    Optional<ServiceCategory> findByIdAndBusinessId(String id, String businessId);

    boolean existsByBusinessIdAndNameKey(String businessId, String nameKey);

    boolean existsByBusinessIdAndNameKeyAndIdNot(String businessId, String nameKey, String id);
}
