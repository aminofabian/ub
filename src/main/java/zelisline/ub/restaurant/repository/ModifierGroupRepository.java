package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.restaurant.domain.ModifierGroup;

public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, String> {

    Optional<ModifierGroup> findByIdAndBusinessId(String id, String businessId);

    List<ModifierGroup> findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(String businessId);
}
