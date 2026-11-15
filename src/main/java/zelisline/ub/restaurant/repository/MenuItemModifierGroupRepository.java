package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.restaurant.domain.MenuItemModifierGroup;

public interface MenuItemModifierGroupRepository extends JpaRepository<MenuItemModifierGroup, String> {

    Optional<MenuItemModifierGroup> findByIdAndBusinessId(String id, String businessId);

    List<MenuItemModifierGroup> findByBusinessIdOrderBySortOrderAsc(String businessId);

    List<MenuItemModifierGroup> findByBusinessIdAndMenuItemIdOrderBySortOrderAsc(String businessId, String menuItemId);

    void deleteByBusinessIdAndMenuItemId(String businessId, String menuItemId);

    void deleteByBusinessIdAndGroupId(String businessId, String groupId);
}
