package zelisline.ub.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.catalog.domain.ItemPackOption;

public interface ItemPackOptionRepository extends JpaRepository<ItemPackOption, String> {

    List<ItemPackOption> findByItemIdOrderBySortOrderAscIdAsc(String itemId);

    List<ItemPackOption> findByItemIdAndActiveTrueOrderBySortOrderAscIdAsc(String itemId);

    List<ItemPackOption> findByItemIdInAndActiveTrueOrderBySortOrderAscIdAsc(java.util.Collection<String> itemIds);

    List<ItemPackOption> findByBusinessIdAndItemId(String businessId, String itemId);

    Optional<ItemPackOption> findByIdAndBusinessIdAndItemId(String id, String businessId, String itemId);
}
