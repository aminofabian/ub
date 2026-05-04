package zelisline.ub.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.catalog.domain.ItemType;

public interface ItemTypeRepository extends JpaRepository<ItemType, String> {

    List<ItemType> findByBusinessIdOrderBySortOrderAsc(String businessId);

    Optional<ItemType> findByIdAndBusinessId(String id, String businessId);

    Optional<ItemType> findByBusinessIdAndTypeKey(String businessId, String typeKey);

    boolean existsByBusinessIdAndTypeKey(String businessId, String typeKey);
}
