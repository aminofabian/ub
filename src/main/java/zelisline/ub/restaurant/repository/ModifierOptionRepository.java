package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.restaurant.domain.ModifierOption;

public interface ModifierOptionRepository extends JpaRepository<ModifierOption, String> {

    Optional<ModifierOption> findByIdAndBusinessId(String id, String businessId);

    List<ModifierOption> findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(String businessId);

    List<ModifierOption> findByBusinessIdAndGroupIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(
            String businessId, String groupId);
}
