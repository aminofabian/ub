package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.restaurant.domain.MenuItem;

public interface MenuItemRepository extends JpaRepository<MenuItem, String> {

    Optional<MenuItem> findByIdAndBusinessId(String id, String businessId);

    List<MenuItem> findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(String businessId);

    List<MenuItem> findByBusinessIdAndIdIn(String businessId, Collection<String> ids);
}
