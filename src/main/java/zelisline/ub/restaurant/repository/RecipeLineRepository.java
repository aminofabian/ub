package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.restaurant.domain.RecipeLine;

public interface RecipeLineRepository extends JpaRepository<RecipeLine, String> {

    List<RecipeLine> findByBusinessIdAndMenuItemIdOrderBySortOrderAsc(String businessId, String menuItemId);

    List<RecipeLine> findByBusinessIdAndModifierOptionIdOrderBySortOrderAsc(String businessId, String modifierOptionId);

    List<RecipeLine> findByBusinessIdAndModifierOptionIdInOrderBySortOrderAsc(
            String businessId, Collection<String> modifierOptionIds);

    List<RecipeLine> findByBusinessIdAndProducedItemIdOrderBySortOrderAsc(String businessId, String producedItemId);

    void deleteByBusinessIdAndProducedItemId(String businessId, String producedItemId);

    List<RecipeLine> findByBusinessId(String businessId);

    void deleteByBusinessIdAndMenuItemId(String businessId, String menuItemId);

    void deleteByBusinessIdAndModifierOptionId(String businessId, String modifierOptionId);
}
