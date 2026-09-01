package zelisline.ub.catalog.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.catalog.domain.Aisle;

public interface AisleRepository extends JpaRepository<Aisle, String> {

    List<Aisle> findByBusinessIdOrderBySortOrderAsc(String businessId);

    List<Aisle> findByBusinessIdAndIdIn(String businessId, Collection<String> ids);

    Optional<Aisle> findByIdAndBusinessId(String id, String businessId);

    Optional<Aisle> findByBusinessIdAndCode(String businessId, String code);

    boolean existsByBusinessIdAndCode(String businessId, String code);
}
