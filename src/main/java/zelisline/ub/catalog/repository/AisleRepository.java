package zelisline.ub.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.catalog.domain.Aisle;

public interface AisleRepository extends JpaRepository<Aisle, String> {

    List<Aisle> findByBusinessIdOrderBySortOrderAsc(String businessId);

    Optional<Aisle> findByIdAndBusinessId(String id, String businessId);

    boolean existsByBusinessIdAndCode(String businessId, String code);
}
